import org.minviewer.app.*;
import java.io.*;
import java.util.*;
public class InkStoreProbe {
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        File root=new File(args[0],UUID.randomUUID().toString());
        PdfInkStore store=new PdfInkStore(root);
        byte[] original="original-unchanged".getBytes("UTF-8");
        PdfInkNote note=store.create(new ByteArrayInputStream(original),"sample.pdf",2);
        InkStroke stroke=new InkStroke(1,.004f,0xffff0000,Arrays.asList(new InkPoint(.2f,.3f,.7f,100),new InkPoint(.6f,.5f,.9f,150)));
        PdfInkNote saved=new PdfInkNote(note.getId(),note.getName(),2,Arrays.asList(stroke));
        store.save(saved);check(store.load(note.getId()).equals(saved),"stroke restore mismatch");
        File broken=new File(root,note.getId()+"/note.json.new");check(broken.mkdir(),"fault setup failed");
        try(FileOutputStream out=new FileOutputStream(new File(broken,"block"))){out.write(1);}
        boolean failed=false;try{store.save(note);}catch(Exception expected){check(expected instanceof IOException,"wrong write error");failed=true;}
        check(failed,"save failure not reported");check(store.load(note.getId()).equals(saved),"last saved strokes lost");
        broken.listFiles()[0].delete();broken.delete();
        boolean rejected=false;
        try{store.save(new PdfInkNote(note.getId(),note.getName(),2,Arrays.asList(new InkStroke(3,.004f,0,stroke.getPoints()))));}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"invalid page accepted");check(store.load(note.getId()).equals(saved),"invalid stroke replaced saved data");
        check(Arrays.equals(original,java.nio.file.Files.readAllBytes(store.original(note.getId()).toPath())),"original changed");
        System.out.println("INK_RESTORE_WRITE_FAILURE_INVALID_PAGE_ORIGINAL_PASS");
    }
}
