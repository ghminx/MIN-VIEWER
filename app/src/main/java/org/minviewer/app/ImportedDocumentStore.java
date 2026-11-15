package org.minviewer.app;

import java.io.*;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

public final class ImportedDocumentStore {
    private static final long LIMIT = 512L * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final Set<String> FORMATS = new HashSet<>(Arrays.asList("pdf","hwp","hwpx","docx","pptx","xlsx","csv"));
    private final File root;

    public static final class Entry {
        public final String id, name, sha256, noteId;
        public final long bytes;
        Entry(String id,String name,String sha256,long bytes) {this(id,name,sha256,bytes,null);}
        Entry(String id,String name,String sha256,long bytes,String noteId) {this.id=id;this.name=name;this.sha256=sha256;this.bytes=bytes;this.noteId=noteId;}
    }
    public static final class Prepared {
        public final List<Entry> matches;
        public final String sha256;
        public final long bytes;
        private final Stage stage;
        private final Entry entry;
        Prepared(Stage stage,Entry entry,List<Entry> matches) {this.stage=stage;this.entry=entry;this.matches=Collections.unmodifiableList(matches);this.sha256=entry.sha256;this.bytes=entry.bytes;}
    }
    private static final class Stage {
        final File directory;
        final FileChannel channel;
        final FileLock lock;
        Stage(File directory,FileChannel channel,FileLock lock){this.directory=directory;this.channel=channel;this.lock=lock;}
        void close() throws IOException {if(lock.isValid())lock.release();if(channel.isOpen())channel.close();}
    }
    public ImportedDocumentStore(File root) {this.root=root;}
    private static MessageDigest digest() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static String hex(byte[] bytes) {StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static void canceled(BooleanSupplier check) throws InterruptedIOException {if(check.getAsBoolean())throw new InterruptedIOException("작업을 취소했습니다.");}
    private static void validName(String name) {
        if(name==null || name.isEmpty() || name.length()>255 || name.indexOf('/')>=0 || name.indexOf('\\')>=0 || name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("잘못된 문서 이름입니다.");
        int dot=name.lastIndexOf('.');if(dot<0 || !FORMATS.contains(name.substring(dot+1).toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("지원하지 않는 문서 형식입니다.");
    }
    private void ensureRoot() throws IOException {if(!root.isDirectory() && !root.mkdirs())throw new IOException("보관함을 만들 수 없습니다.");}
    private File directory(String id) {if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("잘못된 문서 ID입니다.");return new File(root,id);}
    private static void removeStage(File dir) {File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();}
    private Stage newStage() throws IOException {
        ensureRoot();
        try(FileChannel guard=new RandomAccessFile(new File(root,".mutation.lock"),"rw").getChannel();FileLock ignored=guard.lock()) {
            File dir=new File(root,".pending-"+UUID.randomUUID());
            if(!dir.mkdir())throw new IOException("보관 준비 폴더를 만들 수 없습니다.");
            try {
                FileChannel channel=new RandomAccessFile(new File(dir,".active"),"rw").getChannel();
                try{return new Stage(dir,channel,channel.lock());}catch(IOException|RuntimeException error){channel.close();throw error;}
            } catch(IOException|RuntimeException error){removeStage(dir);throw error;}
        }
    }
    private void discardStage(Stage stage) throws IOException {
        try(FileChannel guard=new RandomAccessFile(new File(root,".mutation.lock"),"rw").getChannel();FileLock ignored=guard.lock()) {
            stage.close();removeStage(stage.directory);
            if(stage.directory.exists())throw new IOException("보관 준비 파일을 정리할 수 없습니다.");
        }
    }
    public void cleanupAbandoned() throws IOException {synchronized(LOCK){
        if(!root.exists())return;
        try(FileChannel guard=new RandomAccessFile(new File(root,".mutation.lock"),"rw").getChannel();FileLock ignored=guard.lock()) {
            File[] dirs=root.listFiles();if(dirs==null)throw new IOException("보관함을 읽을 수 없습니다.");
            for(File dir:dirs) {
                if(!dir.isDirectory() || !dir.getName().matches("\\.(pending|deleted)-[0-9a-f-]{36}"))continue;
                File active=new File(dir,".active");boolean available=false;
                try(FileChannel channel=new RandomAccessFile(active,"rw").getChannel()) {
                    try(FileLock lock=channel.tryLock()){available=lock!=null;}catch(OverlappingFileLockException busy){available=false;}
                }
                if(available){removeStage(dir);if(dir.exists())throw new IOException("중단된 문서의 준비 파일을 정리할 수 없습니다.");}
            }
        }
    }}
    private static long copy(InputStream input,File target,MessageDigest digest,BooleanSupplier check,long limit) throws IOException {
        long total=0;byte[] buf=new byte[65536];
        try(FileOutputStream out=new FileOutputStream(target)){int n;while((n=input.read(buf))!=-1){canceled(check);if(n==0)continue;total+=n;if(total>limit)throw new IOException("문서 크기 제한을 초과했습니다.");out.write(buf,0,n);digest.update(buf,0,n);}canceled(check);out.getFD().sync();}
        if(total==0)throw new IOException("빈 문서는 보관할 수 없습니다.");return total;
    }
    private static void writeMetadata(File file,Entry entry) throws IOException {
        Properties p=new Properties();p.setProperty("format","MIN_VIEWER_DOCUMENT");p.setProperty("version","1");p.setProperty("name",entry.name);p.setProperty("sha256",entry.sha256);p.setProperty("bytes",Long.toString(entry.bytes));if(entry.noteId!=null)p.setProperty("noteId",entry.noteId);
        try(FileOutputStream out=new FileOutputStream(file)){p.store(out,null);out.getFD().sync();}
    }
    private Entry read(File dir) throws IOException {
        String id=dir.getName();directory(id);Properties p=new Properties();
        try(FileInputStream input=new FileInputStream(new File(dir,"metadata.properties"))){p.load(input);}
        if(!"MIN_VIEWER_DOCUMENT".equals(p.getProperty("format")) || !"1".equals(p.getProperty("version")))throw new IOException("지원하지 않는 문서 정보입니다.");
        String name=p.getProperty("name"),hash=p.getProperty("sha256"),count=p.getProperty("bytes");
        validName(name);if(hash==null || !hash.matches("[0-9a-f]{64}") || count==null)throw new IOException("보관 문서 정보가 손상됐습니다.");
        long bytes;try{bytes=Long.parseLong(count);}catch(NumberFormatException e){throw new IOException("보관 문서 크기가 잘못됐습니다.",e);}
        File file=new File(dir,"source");if(bytes<=0 || bytes>LIMIT || !file.isFile() || file.length()!=bytes)throw new IOException("보관 문서가 없거나 크기가 다릅니다.");
        String noteId=p.getProperty("noteId");
        if(noteId!=null && !UUID.fromString(noteId).toString().equals(noteId))throw new IOException("필기 문서 정보가 잘못됐습니다.");
        return new Entry(id,name,hash,bytes,noteId);
    }
    public List<Entry> all() throws IOException {synchronized(LOCK){
        if(!root.exists())return Collections.emptyList();File[] dirs=root.listFiles();if(dirs==null)throw new IOException("보관함을 읽을 수 없습니다.");
        List<Entry> result=new ArrayList<>();for(File dir:dirs){if(!dir.isDirectory() || dir.getName().startsWith("."))continue;
            try{result.add(read(dir));}catch(IllegalArgumentException error){throw new IOException("보관 문서 정보가 손상됐습니다.",error);}}
        return result;
    }}
    public File file(String id) throws IOException {synchronized(LOCK){File dir=directory(id);read(dir);return new File(dir,"source");}}
    public Entry get(String id) throws IOException {synchronized(LOCK){return read(directory(id));}}
    public Entry attachNote(String id,String noteId) throws IOException {synchronized(LOCK){
        ensureRoot();
        try(FileChannel channel=new RandomAccessFile(new File(root,".mutation.lock"),"rw").getChannel();FileLock ignored=channel.lock()) {
        if(!UUID.fromString(noteId).toString().equals(noteId))throw new IllegalArgumentException("잘못된 필기 문서 ID입니다.");
        File dir=directory(id);Entry old=read(dir);
        if(old.noteId!=null && !old.noteId.equals(noteId))throw new IOException("이미 다른 필기가 연결된 문서입니다.");
        Entry entry=new Entry(old.id,old.name,old.sha256,old.bytes,noteId);
        File pending=new File(dir,"metadata.pending");
        try {writeMetadata(pending,entry);Files.move(pending.toPath(),new File(dir,"metadata.properties").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally {pending.delete();}
        return entry;
        }
    }}
    public void delete(String id) throws IOException {synchronized(LOCK){
        ensureRoot();
        try(FileChannel channel=new RandomAccessFile(new File(root,".mutation.lock"),"rw").getChannel();FileLock ignored=channel.lock()) {
        File dir=directory(id);read(dir);File trash=new File(root,".deleted-"+UUID.randomUUID());
        if(!dir.renameTo(trash))throw new IOException("보관 문서를 삭제할 수 없습니다.");
        removeStage(trash);if(trash.exists())throw new IOException("삭제한 문서의 임시 파일을 정리할 수 없습니다.");
        }
    }}
    public Prepared prepare(InputStream input,String name,BooleanSupplier check) throws IOException {synchronized(LOCK){
        validName(name);Stage stage=newStage();String id=stage.directory.getName().substring(".pending-".length());
        try(InputStream source=input){MessageDigest hash=digest();long bytes=copy(source,new File(stage.directory,"source"),hash,check,LIMIT);Entry entry=new Entry(id,name,hex(hash.digest()),bytes);writeMetadata(new File(stage.directory,"metadata.properties"),entry);
            List<Entry> matches=new ArrayList<>();for(Entry existing:all())if(existing.bytes==bytes && existing.sha256.equals(entry.sha256))matches.add(existing);
            canceled(check);return new Prepared(stage,entry,matches);
        }catch(IOException|RuntimeException e){try{discardStage(stage);}catch(IOException cleanup){e.addSuppressed(cleanup);}throw e;}
    }}
    public Entry commit(Prepared prepared) throws IOException {synchronized(LOCK){
        try(FileChannel guard=new RandomAccessFile(new File(root,".mutation.lock"),"rw").getChannel();FileLock ignored=guard.lock()) {
            File target=directory(prepared.entry.id);prepared.stage.close();
            if(!new File(prepared.stage.directory,".active").delete() || target.exists() || !prepared.stage.directory.renameTo(target))throw new IOException("문서 보관을 완료하지 못했습니다.");
            return prepared.entry;
        }
    }}
    public void discard(Prepared prepared) throws IOException {synchronized(LOCK){discardStage(prepared.stage);}}
    public void backup(String id,OutputStream output,BooleanSupplier check) throws IOException {synchronized(LOCK){
        File dir=directory(id);Entry entry=read(dir);if(entry.noteId!=null)throw new IOException("필기가 연결된 문서는 필기 백업을 사용해 주세요.");MessageDigest hash=digest();try(InputStream in=new FileInputStream(new File(dir,"source"))){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1){canceled(check);hash.update(buf,0,n);}}
        if(!hex(hash.digest()).equals(entry.sha256))throw new IOException("보관 문서가 변경됐습니다.");
        try(ZipOutputStream zip=new ZipOutputStream(output)) {for(String n:Arrays.asList("metadata.properties","source")){zip.putNextEntry(new ZipEntry(n));try(InputStream in=new FileInputStream(new File(dir,n))){byte[] buf=new byte[65536];int read;while((read=in.read(buf))!=-1){canceled(check);zip.write(buf,0,read);}}zip.closeEntry();}}
    }}
    public Entry restore(InputStream input,BooleanSupplier check) throws IOException {synchronized(LOCK){
        Stage staged=newStage();File stage=staged.directory;String id=stage.getName().substring(".pending-".length());
        try(ZipInputStream zip=new ZipInputStream(input)){
            Set<String> seen=new HashSet<>();ZipEntry item;while((item=zip.getNextEntry())!=null){String name=item.getName();if(item.isDirectory() || !Arrays.asList("metadata.properties","source").contains(name) || !seen.add(name))throw new IOException("잘못된 백업 항목입니다.");
                MessageDigest hash=digest();copy(zip,new File(stage,name),hash,check,name.equals("source")?LIMIT:16384);zip.closeEntry();}
            if(seen.size()!=2)throw new IOException("백업 항목이 누락됐습니다.");
            Properties p=new Properties();try(FileInputStream in=new FileInputStream(new File(stage,"metadata.properties"))){p.load(in);}if(!"MIN_VIEWER_DOCUMENT".equals(p.getProperty("format")) || !"1".equals(p.getProperty("version")))throw new IOException("지원하지 않는 백업 형식입니다.");
            if(p.containsKey("noteId"))throw new IOException("읽기 문서 백업에 필기 연결 정보를 포함할 수 없습니다.");
            String name=p.getProperty("name"),expected=p.getProperty("sha256");validName(name);
            MessageDigest hash=digest();long bytes=0;try(InputStream in=new FileInputStream(new File(stage,"source"))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){canceled(check);hash.update(b,0,n);bytes+=n;}}
            if(!hex(hash.digest()).equals(expected) || !Long.toString(bytes).equals(p.getProperty("bytes")))throw new IOException("백업이 손상됐습니다.");
            canceled(check);Entry entry=new Entry(id,name,expected,bytes);return commit(new Prepared(staged,entry,Collections.emptyList()));
        }finally{discardStage(staged);}
    }}
}
