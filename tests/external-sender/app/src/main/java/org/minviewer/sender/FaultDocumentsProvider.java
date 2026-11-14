package org.minviewer.sender;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** 시험 전용 저장 위치에서 일부 기록 후 실제 ENOSPC 반환. */
public class FaultDocumentsProvider extends DocumentsProvider {
    private File directory;
    private Handler handler;
    private static final String[] ROOT_COLUMNS={Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES};
    private static final String[] DOCUMENT_COLUMNS={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_SIZE,Document.COLUMN_FLAGS};

    @Override public boolean onCreate() {
        directory=new File(getContext().getFilesDir(),"fault-documents");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("시험 저장 위치 생성 실패");
        HandlerThread thread=new HandlerThread("fault-storage");thread.start();handler=new Handler(thread.getLooper());
        return true;
    }

    private File file(String id)throws FileNotFoundException {
        if(!id.matches("[a-f0-9-]{36}"))throw new FileNotFoundException("잘못된 시험 문서 ID");
        return new File(directory,id);
    }

    private void row(MatrixCursor cursor,String id)throws FileNotFoundException {
        boolean root=id.equals("root");File item=root?directory:file(id);
        if(!item.exists())throw new FileNotFoundException(id);
        android.content.SharedPreferences metadata=getContext().getSharedPreferences("documents",0);
        MatrixCursor.RowBuilder row=cursor.newRow();
        for(String column:cursor.getColumnNames()) {
            switch(column) {
                case Document.COLUMN_DOCUMENT_ID:row.add(id);break;
                case Document.COLUMN_DISPLAY_NAME:row.add(root?"MIN test storage":metadata.getString(id+".name",id));break;
                case Document.COLUMN_MIME_TYPE:row.add(root?Document.MIME_TYPE_DIR:metadata.getString(id+".mime","application/octet-stream"));break;
                case Document.COLUMN_SIZE:row.add(root?0:item.length());break;
                case Document.COLUMN_FLAGS:row.add(root?Document.FLAG_DIR_SUPPORTS_CREATE:Document.FLAG_SUPPORTS_WRITE|Document.FLAG_SUPPORTS_DELETE);break;
                default:row.add(null);
            }
        }
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor result=new MatrixCursor(projection==null?ROOT_COLUMNS:projection);
        MatrixCursor.RowBuilder row=result.newRow();
        for(String column:result.getColumnNames()) {
            switch(column) {
                case Root.COLUMN_ROOT_ID:case Root.COLUMN_DOCUMENT_ID:row.add("root");break;
                case Root.COLUMN_TITLE:row.add("MIN test storage");break;
                case Root.COLUMN_FLAGS:row.add(Root.FLAG_SUPPORTS_CREATE|Root.FLAG_LOCAL_ONLY);break;
                case Root.COLUMN_MIME_TYPES:row.add("*/*");break;
                default:row.add(null);
            }
        }
        return result;
    }

    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {
        MatrixCursor result=new MatrixCursor(projection==null?DOCUMENT_COLUMNS:projection);row(result,id);return result;
    }

    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort)throws FileNotFoundException {
        if(!parent.equals("root"))throw new FileNotFoundException(parent);
        MatrixCursor result=new MatrixCursor(projection==null?DOCUMENT_COLUMNS:projection);
        File[] children=directory.listFiles();if(children==null)throw new FileNotFoundException("시험 폴더 읽기 실패");
        for(File child:children)row(result,child.getName());return result;
    }

    @Override public String createDocument(String parent,String mime,String name)throws FileNotFoundException {
        if(!parent.equals("root"))throw new FileNotFoundException(parent);
        String id=UUID.randomUUID().toString();
        try{if(!file(id).createNewFile())throw new IOException("시험 파일 중복");}
        catch(IOException error){throw new FileNotFoundException(error.toString());}
        getContext().getSharedPreferences("documents",0).edit().putString(id+".name",name).putString(id+".mime",mime).commit();return id;
    }

    @Override public void deleteDocument(String id)throws FileNotFoundException {
        if(!file(id).delete())throw new FileNotFoundException("시험 파일 삭제 실패");
        getContext().getSharedPreferences("documents",0).edit().remove(id+".name").remove(id+".mime").commit();
    }

    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {
        File target=file(id);
        try {
            File setting=new File(getContext().getFilesDir(),"fault-mode");
            boolean full=setting.isFile()&&new String(Files.readAllBytes(setting.toPath()),StandardCharsets.UTF_8).trim().equals("full");
            if(!mode.contains("w")||!full)return ParcelFileDescriptor.open(target,ParcelFileDescriptor.parseMode(mode));
            RandomAccessFile output=new RandomAccessFile(target,"rw");
            return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_WRITE,new ProxyFileDescriptorCallback(){
                @Override public long onGetSize(){return 0;}
                @Override public int onWrite(long offset,int size,byte[] bytes)throws ErrnoException {
                    if(offset>=4096){
                        try{Files.write(new File(getContext().getFilesDir(),"fault-event").toPath(),("ENOSPC offset="+offset).getBytes(StandardCharsets.UTF_8));}
                        catch(IOException error){throw new IllegalStateException(error);}
                        throw new ErrnoException("test storage full",OsConstants.ENOSPC);
                    }
                    int count=(int)Math.min(size,4096-offset);
                    try{output.seek(offset);output.write(bytes,0,count);return count;}
                    catch(IOException error){throw new ErrnoException("test output",OsConstants.EIO,error);}
                }
                @Override public void onFsync()throws ErrnoException {
                    try{output.getFD().sync();}catch(IOException error){throw new ErrnoException("test sync",OsConstants.EIO,error);}
                }
                @Override public void onRelease(){try{output.close();}catch(IOException error){throw new IllegalStateException(error);}}
            },handler);
        }catch(IOException error){throw new FileNotFoundException(error.toString());}
    }
}
