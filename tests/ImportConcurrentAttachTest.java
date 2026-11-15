import org.minviewer.app.ImportedDocumentStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ImportConcurrentAttachTest {
    public static void main(String[] args) throws Exception {
        if(args.length>0) {
            File root=new File(args[0]);
            while(!new File(root,"go").exists()) Thread.sleep(1);
            try { new ImportedDocumentStore(root).attachNote(args[1],args[2]); System.out.println("OK"); }
            catch(IOException e) { System.out.println("REJECTED"); }
            return;
        }
        for(int i=0;i<12;i++) {
            File root=Files.createTempDirectory("min-import-race").toFile();
            ImportedDocumentStore store=new ImportedDocumentStore(root);
            String id=store.commit(store.prepare(new ByteArrayInputStream(new byte[]{1}),"a.pdf",()->false)).id;
            String java=Paths.get(System.getProperty("java.home"),"bin","java").toString();
            String cp=System.getProperty("java.class.path");
            Process a=new ProcessBuilder(java,"-cp",cp,"ImportConcurrentAttachTest",root.toString(),id,UUID.randomUUID().toString()).redirectErrorStream(true).start();
            Process b=new ProcessBuilder(java,"-cp",cp,"ImportConcurrentAttachTest",root.toString(),id,UUID.randomUUID().toString()).redirectErrorStream(true).start();
            Thread.sleep(200);new File(root,"go").createNewFile();
            String ar=new String(a.getInputStream().readAllBytes());String br=new String(b.getInputStream().readAllBytes());
            if(a.waitFor()!=0 || b.waitFor()!=0 || !(ar.contains("OK") && br.contains("REJECTED") || br.contains("OK") && ar.contains("REJECTED")))
                throw new AssertionError("exactly one attach must succeed: "+ar+" / "+br);
        }
        System.out.println("IMPORT_CONCURRENT_ATTACH_PASS");
    }
}
