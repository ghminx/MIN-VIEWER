import org.minviewer.app.*;
import java.io.*;
import java.util.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import java.security.MessageDigest;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicInteger;
public class InkBackupProbe {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static Map<String,byte[]> unpack(byte[] bytes) throws Exception {
        Map<String,byte[]> files=new LinkedHashMap<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=zip.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=zip.read(b))>=0)out.write(b,0,n);files.put(e.getName(),out.toByteArray());}}
        return files;
    }
    static byte[] pack(Map<String,byte[]> files) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> e:files.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}
        return out.toByteArray();
    }
    static String sha(byte[] bytes) throws Exception {StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b));return s.toString();}
    static byte[] withInk(byte[] valid,String ink) throws Exception {
        Map<String,byte[]> files=unpack(valid);byte[] bytes=ink.getBytes(StandardCharsets.UTF_8);files.put("note.json",bytes);
        JSONObject manifest=new JSONObject(new String(files.get("manifest.json"),StandardCharsets.UTF_8));manifest.put("inkBytes",bytes.length);manifest.put("inkSha256",sha(bytes));files.put("manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));return pack(files);
    }
    static void reject(PdfInkStore store,PdfInkNote saved,byte[] bytes,String label) throws Exception {
        boolean rejected=false;try{store.restore(new ByteArrayInputStream(bytes));}catch(Exception expected){rejected=true;}
        check(rejected,label+" accepted");check(store.all().size()==2 && store.load(saved.getId()).equals(saved),label+" changed existing notes");check(store.cleanup(),label+" cleanup failed");
    }
    static class BrokenOutput extends OutputStream {
        int count;
        @Override public void write(int b) throws IOException {if(++count>100)throw new IOException("simulated output failure");}
    }
    public static void main(String[] args) throws Exception {
        File root=new File(args[0],UUID.randomUUID().toString());
        PdfInkStore store=new PdfInkStore(root);
        byte[] original=Files.readAllBytes(new File(args[1]).toPath());
        PdfInkNote note=store.create(new ByteArrayInputStream(original),"backup-source.pdf",1);
        List<InkPoint> points=Arrays.asList(new InkPoint(.2f,.3f,.7f,100),new InkPoint(.6f,.5f,.9f,150));
        PdfInkNote saved=new PdfInkNote(note.getId(),note.getName(),1,Arrays.asList(new InkStroke(0,.004f,0xffff0000,points),new InkStroke(0,.02f,0x60ffd700,points,"highlighter")));
        store.save(saved);
        ByteArrayOutputStream out=new ByteArrayOutputStream();store.backup(note.getId(),out);
        PdfInkNote restored=store.restore(new ByteArrayInputStream(out.toByteArray()));
        check(!restored.getId().equals(saved.getId()),"restore overwrote original ID");
        check(restored.getName().equals(saved.getName()) && restored.getPages()==1 && restored.getStrokes().equals(saved.getStrokes()),"restored annotation changed");
        check(Arrays.equals(original,Files.readAllBytes(store.original(restored.getId()).toPath())),"restored original changed");
        check(store.load(saved.getId()).equals(saved) && store.all().size()==2,"existing note changed");
        System.out.println("BACKUP_NEW_ID_ORIGINAL_STYLES_ROUNDTRIP_PASS");
        PdfInkNote longName=new PdfInkNote(saved.getId(),String.join("",Collections.nCopies(4097,"a")),1,saved.getStrokes());
        boolean limitRejected=false;
        try { store.save(longName); } catch(IllegalArgumentException expected) { limitRejected=true; }
        check(limitRejected,"save accepted ink that backup cannot restore");
        check(store.load(saved.getId()).equals(saved),"rejected save replaced previous ink");
        ByteArrayOutputStream preserved=new ByteArrayOutputStream();store.backup(saved.getId(),preserved);
        check(Arrays.equals(unpack(preserved.toByteArray()).get("note.json"),unpack(out.toByteArray()).get("note.json")),"rejected save changed backed up ink");
        System.out.println("SAVE_LIMIT_PRESERVES_PREVIOUS_BACKUP_PASS");
        int limitCase=0;
        for(List<InkPoint> excessivePoints:Arrays.asList(
                Collections.nCopies(130000,new InkPoint(0,0,1,0)),
                Collections.nCopies(120000,new InkPoint(.12345679f,.2345679f,.8765432f,Long.MAX_VALUE)))) {
            limitRejected=false;
            try { store.save(new PdfInkNote(saved.getId(),saved.getName(),1,Collections.singletonList(new InkStroke(0,.004f,0xffff0000,excessivePoints)))); }
            catch(IllegalArgumentException expected) { check(expected.getMessage().contains(limitCase==0?"항목 수":"크기"),"wrong limit rejected ink");limitRejected=true; }
            check(limitRejected&&store.load(saved.getId()).equals(saved),"size or token limit damaged saved note");
            limitCase++;
        }
        int before=store.all().size();
        for(byte[] input:Arrays.asList(new byte[0],original)) {
            boolean invalid=false;
            try { store.create(new ByteArrayInputStream(input),input.length==0?"empty.pdf":longName.getName(),1); }
            catch(IllegalArgumentException expected) { invalid=true; }
            check(invalid&&store.all().size()==before,"failed create published a note");
        }
        System.out.println("SAVE_BYTE_TOKEN_LIMIT_AND_FAILED_CREATE_PASS");
        try(RandomAccessFile large=new RandomAccessFile(store.original(saved.getId()),"rw")){large.setLength(512L*1024*1024+1);}
        limitRejected=false;try{store.save(saved);}catch(IllegalArgumentException expected){limitRejected=true;}
        check(limitRejected&&store.load(saved.getId()).equals(saved),"legacy oversized PDF lost ink");
        Files.write(store.original(saved.getId()).toPath(),original);
        byte[] valid=out.toByteArray();Map<String,byte[]> files=unpack(valid);
        files.put("../escape",new byte[]{1});reject(store,saved,pack(files),"path traversal");check(!new File(root,"escape").exists(),"path escaped");
        files=unpack(valid);files.remove("note.json");reject(store,saved,pack(files),"missing entry");
        reject(store,saved,Arrays.copyOf(valid,valid.length-22),"truncated archive");
        byte[] excessive=valid.clone();excessive[excessive.length-22+10]=(byte)0xff;excessive[excessive.length-22+11]=(byte)0xff;reject(store,saved,excessive,"excessive central directory count");
        files=unpack(valid);files.get("original.pdf")[0]^=1;reject(store,saved,pack(files),"checksum mismatch");
        files=unpack(valid);files.put("original.pdf","not a PDF".getBytes(StandardCharsets.UTF_8));
        JSONObject badPdfManifest=new JSONObject(new String(files.get("manifest.json"),StandardCharsets.UTF_8));
        badPdfManifest.put("pdfBytes",files.get("original.pdf").length).put("pdfSha256",sha(files.get("original.pdf")));
        files.put("manifest.json",badPdfManifest.toString().getBytes(StandardCharsets.UTF_8));
        reject(store,saved,pack(files),"matching hash but unreadable PDF");
        files=unpack(valid);files.put("n0te.json",files.get("note.json"));
        byte[] duplicate=new String(pack(files),StandardCharsets.ISO_8859_1).replace("n0te.json","note.json").getBytes(StandardCharsets.ISO_8859_1);reject(store,saved,duplicate,"duplicate entry");
        String ink=new String(unpack(valid).get("note.json"),StandardCharsets.UTF_8);
        reject(store,saved,withInk(valid,ink.replace("\"page\":0","\"page\":9")),"invalid stroke page");
        reject(store,saved,withInk(valid,ink.replace("\"pages\":1","\"pages\":2")),"PDF page mismatch");
        reject(store,saved,withInk(valid,ink.replace("\"version\":2","\"version\":9")),"unsupported schema");
        reject(store,saved,withInk(valid,"{\"version\":2,\"version\":2}"),"duplicate JSON field");
        String deep="0";for(int i=0;i<20;i++)deep="["+deep+"]";reject(store,saved,withInk(valid,"{\"extra\":"+deep+"}"),"excessive nesting");
        StringBuilder values=new StringBuilder("{\"extra\":[0");for(int i=0;i<750000;i++)values.append(",0");values.append("]}");reject(store,saved,withInk(valid,values.toString()),"excessive JSON tokens");
        files=unpack(valid);files.put("note.json",new byte[8*1024*1024+1]);reject(store,saved,pack(files),"oversized ink");
        boolean failed=false;try{store.backup(saved.getId(),new BrokenOutput());}catch(Exception expected){check(expected instanceof IOException,"wrong output failure");failed=true;}
        check(failed && store.load(saved.getId()).equals(saved),"output failure lost source");
        AtomicInteger ticks=new AtomicInteger();boolean canceled=false;
        try{store.restore(new ByteArrayInputStream(valid),()->ticks.incrementAndGet()>=5);}catch(Exception expected){check(expected instanceof InterruptedIOException,"wrong cancel failure");canceled=true;}
        check(canceled && store.all().size()==2 && store.load(saved.getId()).equals(saved),"cancel published incomplete restore");
        System.out.println("CORRUPT_PATH_DUPLICATE_HASH_SCHEMA_LIMIT_CANCEL_WRITE_FAILURE_PASS");
        check(store.delete(restored.getId()),"delete failed");check(store.all().size()==1 && store.load(saved.getId()).equals(saved),"delete changed other note");
        boolean resaved=false;try{store.save(restored);resaved=true;}catch(IllegalArgumentException expected){}
        check(!resaved && store.all().size()==1,"deleted note resurrected");
        PdfInkNote broken=store.create(new ByteArrayInputStream(original),"broken.pdf",1);Files.write(new File(root,broken.getId()+"/note.json").toPath(),"broken".getBytes(StandardCharsets.UTF_8));
        check(store.all().stream().anyMatch(e->e.getId().equals(broken.getId())&&e.getError()!=null),"corrupt note hidden");check(store.delete(broken.getId()),"corrupt note not removable");
        File orphan=new File(root,".restore-"+UUID.randomUUID());check(orphan.mkdir(),"orphan setup");Files.write(new File(orphan,"partial").toPath(),new byte[]{1});check(store.cleanup()&&!orphan.exists(),"interrupted restore cleanup");
        check(Arrays.equals(original,Files.readAllBytes(store.original(saved.getId()).toPath())),"source original changed");
        System.out.println("DELETE_NO_RESURRECTION_CORRUPT_LIST_ORPHAN_CLEANUP_PASS");
    }
}
