import org.minviewer.app.ImportedDocumentStore;
import java.io.*;
import java.util.*;

public class ImportRecoveryDevice {
    public static void main(String[] args) throws Exception {
        File root=new File(args[1]);ImportedDocumentStore store=new ImportedDocumentStore(root);
        switch(args[0]) {
        case "init":
            if(root.exists())throw new IOException("시험 폴더가 이미 있습니다.");
            store.commit(store.prepare(new ByteArrayInputStream(new byte[]{7}),"saved.pdf",()->false));break;
        case "hold":
            store.prepare(new InputStream(){
                int count;
                public int read(){return -1;}
                public int read(byte[] b,int offset,int length) throws IOException {
                    if(count++==0){Arrays.fill(b,offset,offset+length,(byte)8);return length;}
                    System.out.println("READY "+android.os.Process.myPid());System.out.flush();
                    try{Thread.sleep(120000);}catch(InterruptedException e){throw new InterruptedIOException();}
                    return -1;
                }
            },"pending.pdf",()->false);break;
        case "check":
            store.cleanupAbandoned();
            int pending=Objects.requireNonNull(root.listFiles((dir,name)->name.startsWith(".pending-"))).length;
            if(pending!=Integer.parseInt(args[2]) || store.all().size()!=1 || store.file(store.all().get(0).id).length()!=1)
                throw new AssertionError("저장 또는 준비 파일 상태가 다릅니다.");
            System.out.println("CHECK_PASS "+pending);break;
        case "remove":
            for(ImportedDocumentStore.Entry entry:store.all())store.delete(entry.id);
            if(!new File(root,".mutation.lock").delete() || !root.delete())throw new IOException("시험 폴더 정리 실패");
            break;
        default:throw new IllegalArgumentException("알 수 없는 시험 동작");
        }
    }
}
