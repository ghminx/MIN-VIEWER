import org.minviewer.app.ImportedDocumentStore;
import java.io.*;
import java.nio.file.*;

public class ImportRecoveryTest {
    public static void main(String[] args) throws Exception {
        if(args.length>0) {
            ImportedDocumentStore store=new ImportedDocumentStore(new File(args[0]));
            InputStream slow=new InputStream(){
                int count;
                @Override public int read(){return -1;}
                @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                    if(count++==0){java.util.Arrays.fill(bytes,offset,offset+length,(byte)2);return length;}
                    System.out.println("READY");System.out.flush();
                    try {Thread.sleep(60000);}catch(InterruptedException e){throw new InterruptedIOException();}
                    return -1;
                }
            };
            store.prepare(slow,"pending.pdf",()->false);return;
        }
        File root=Files.createTempDirectory("min-import-recovery").toFile();
        ImportedDocumentStore store=new ImportedDocumentStore(root);
        String id=store.commit(store.prepare(new ByteArrayInputStream(new byte[]{1}),"saved.pdf",()->false)).id;
        String java=Paths.get(System.getProperty("java.home"),"bin","java").toString();
        Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),"ImportRecoveryTest",root.toString()).redirectErrorStream(true).start();
        if(!"READY".equals(new BufferedReader(new InputStreamReader(child.getInputStream())).readLine()))throw new AssertionError("child failed");
        store.cleanupAbandoned();
        if(root.listFiles((d,n)->n.startsWith(".pending-")).length!=1)throw new AssertionError("active import removed");
        child.destroyForcibly();child.waitFor();store.cleanupAbandoned();
        if(root.listFiles((d,n)->n.startsWith(".pending-")).length!=0)throw new AssertionError("abandoned import not removed");
        if(store.file(id).length()!=1)throw new AssertionError("saved document damaged");
        System.out.println("IMPORT_RECOVERY_PASS");
    }
}
