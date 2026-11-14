import org.minviewer.app.CreatedDocumentOutput;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DocumentOutputProbe {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static class Destination extends ContentProvider {
        final File file;
        int opens;
        boolean readOnly;
        ParcelFileDescriptor[] pipe;
        Destination(File file){this.file=file;}
        public boolean onCreate(){return true;}
        public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
        public String getType(Uri u){return "application/pdf";}
        public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
        public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
        public int delete(Uri u,String s,String[] a){throw new AssertionError("external file deleted");}
        public ParcelFileDescriptor openFile(Uri u,String mode) throws FileNotFoundException {
            opens++; return pipe!=null?pipe[1]:ParcelFileDescriptor.open(file,readOnly?ParcelFileDescriptor.MODE_READ_ONLY:ParcelFileDescriptor.parseMode(mode));
        }
    }
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> activityThread=Class.forName("android.app.ActivityThread");
        Object thread=activityThread.getMethod("systemMain").invoke(null);
        Context context=(Context)activityThread.getMethod("getSystemContext").invoke(thread);
        android.app.Application app=android.app.Instrumentation.newApplication(android.app.Application.class,context);
        java.lang.reflect.Field initial=activityThread.getDeclaredField("mInitialApplication");initial.setAccessible(true);initial.set(thread,app);
        File root=new File(args[0],"output-"+UUID.randomUUID());check(root.mkdirs(),"setup");
        File source=new File(root,"source.pdf"),target=new File(root,"target.pdf");
        byte[] bytes=new byte[200000];Arrays.fill(bytes,(byte)42);Files.write(source.toPath(),bytes);
        byte[] previous="previous completed document".getBytes("UTF-8");Files.write(target.toPath(),previous);
        Destination provider=new Destination(target);ContentResolver resolver=ContentResolver.wrap(provider);
        Uri uri=Uri.parse("content://min-output-test/target");
        boolean rejected=false;
        try{CreatedDocumentOutput.INSTANCE.write(resolver,uri,source,()->false);}catch(Exception expected){check(expected instanceof IOException,"wrong destination error");rejected=true;}
        check(rejected&&Arrays.equals(previous,Files.readAllBytes(target.toPath())),"existing destination overwritten");
        System.out.println("EXISTING_DESTINATION_PRESERVED_PASS");
        int opened=provider.opens;boolean canceled=false;
        try{CreatedDocumentOutput.INSTANCE.write(resolver,uri,source,()->true);}catch(Exception expected){check(expected instanceof InterruptedIOException,"wrong cancel error");canceled=true;}
        check(canceled&&provider.opens==opened&&Arrays.equals(previous,Files.readAllBytes(target.toPath())),"pre-cancel touched destination");
        Files.write(target.toPath(),new byte[0]);AtomicInteger ticks=new AtomicInteger();canceled=false;
        try{CreatedDocumentOutput.INSTANCE.write(resolver,uri,source,()->ticks.incrementAndGet()>2);}catch(Exception expected){check(expected instanceof InterruptedIOException,"wrong write cancel error");canceled=true;}
        check(canceled&&target.isFile()&&Arrays.equals(bytes,Files.readAllBytes(source.toPath())),"cancel lost source or deleted destination");
        Files.write(target.toPath(),new byte[0]);provider.readOnly=true;boolean failed=false;
        try{CreatedDocumentOutput.INSTANCE.write(resolver,uri,source,()->false);}catch(Exception expected){check(expected instanceof IOException,"wrong write error");failed=true;}
        check(failed&&target.isFile()&&Arrays.equals(bytes,Files.readAllBytes(source.toPath())),"write failure lost source or destination");
        provider.readOnly=false;
        provider.pipe=ParcelFileDescriptor.createPipe();rejected=false;
        try{CreatedDocumentOutput.INSTANCE.write(resolver,uri,source,()->false);}catch(Exception expected){check(expected instanceof IOException,"wrong unknown size error");rejected=true;}
        finally{provider.pipe[0].close();provider.pipe[1].close();provider.pipe=null;}
        check(rejected,"unverifiable destination accepted");
        Files.write(target.toPath(),new byte[0]);CreatedDocumentOutput.INSTANCE.write(resolver,uri,source,()->false);
        check(Arrays.equals(bytes,Files.readAllBytes(target.toPath())),"new output copy changed");
        System.out.println("CANCEL_WRITE_FAILURE_AND_NEW_DESTINATION_COPY_PASS");
    }
}
