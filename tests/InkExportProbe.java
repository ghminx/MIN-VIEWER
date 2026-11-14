import org.minviewer.app.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class InkExportProbe {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        File output = new File(args[1]); output.mkdirs();
        PdfInkStore store = new PdfInkStore(new File(output, "notes-" + UUID.randomUUID()));
        byte[] original = Files.readAllBytes(new File(args[0]).toPath());
        int pages=args.length>3?Integer.parseInt(args[3]):5;
        PdfInkNote note = store.create(new ByteArrayInputStream(original), "export-source.pdf", pages);
        List<InkStroke> strokes = new ArrayList<>();
        for (int page=0; page<4; page++) {
            strokes.add(new InkStroke(page, .008f, 0xffff0000, Arrays.asList(
                new InkPoint(.2f,.3f,1,0), new InkPoint(.7f,.3f,1,10))));
            strokes.add(new InkStroke(page, .035f, 0x60ffd700, Arrays.asList(
                new InkPoint(.15f,.6f,1,0), new InkPoint(.75f,.6f,1,10)), "highlighter"));
            strokes.add(new InkStroke(page, .02f, 0xff0000ff,
                Collections.singletonList(new InkPoint(.85f,.8f,1,0))));
        }
        PdfInkNote saved = new PdfInkNote(note.getId(),note.getName(),pages,strokes); store.save(saved);
        File target = new File(output,"annotated.pdf");
        byte[] previous="previous completed output".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target.toPath(),previous);
        boolean needsChoice=false;
        try { store.exportPdf(note.getId(),target,()->false); }
        catch (Exception expected) { check(expected instanceof PdfInkExportNeedsMerge,"wrong merge error"); needsChoice=true; }
        check(needsChoice && Arrays.equals(previous,Files.readAllBytes(target.toPath())),"annotation merge must require an explicit choice");
        store.exportPdf(note.getId(),target,()->false,true);
        check(target.isFile() && target.length()>0,"PDF missing");
        check(Arrays.equals(original,Files.readAllBytes(store.original(note.getId()).toPath())),"original changed");
        check(store.load(note.getId()).equals(saved),"ink changed");
        System.out.println("PDF_EXPORT_ORIGINAL_AND_INK_PRESERVED_PASS");
        if(args.length>3)return;
        byte[] completed=Files.readAllBytes(target.toPath());
        boolean canceled=false;
        try { store.exportPdf(note.getId(),target,()->true); }
        catch (Exception expected) { check(expected instanceof InterruptedIOException,"cancel error"); canceled=true; }
        check(canceled && Arrays.equals(completed,Files.readAllBytes(target.toPath())),"cancel lost previous output");
        boolean blocked=false;
        try { store.exportPdf(note.getId(),store.original(note.getId()),()->false); }
        catch (IllegalArgumentException expected) { blocked=true; }
        check(blocked,"source overwrite allowed");
        File obstacle=new File(output,"not-a-directory");Files.write(obstacle.toPath(),new byte[]{1});
        boolean failed=false;
        try { store.exportPdf(note.getId(),new File(obstacle,"output.pdf"),()->false,true); }
        catch (Exception expected) { failed=true; }
        check(failed && store.load(note.getId()).equals(saved),"write failure damaged note");
        PdfInkNote formNote=store.create(new ByteArrayInputStream(original),"form-overlap.pdf",5);
        store.save(new PdfInkNote(formNote.getId(),formNote.getName(),5,Collections.singletonList(new InkStroke(0,.008f,0xffff0000,
            Arrays.asList(new InkPoint(.4f,.25f,1,0),new InkPoint(.5f,.25f,1,10))))));
        boolean formBlocked=false;
        try { store.exportPdf(formNote.getId(),target,()->false,true); }
        catch(IllegalArgumentException expected) { check(expected.getMessage().contains("입력란"),"wrong field overlap error");formBlocked=true; }
        check(formBlocked && Arrays.equals(completed,Files.readAllBytes(target.toPath())),"interactive field silently flattened");
        for(String kind:Arrays.asList("encrypted","signed")) {
            PdfInkNote protectedNote=store.create(new FileInputStream(new File(args[2],kind+".pdf")),kind+".pdf",5);
            boolean rejected=false;
            try { store.exportPdf(protectedNote.getId(),target,()->false); }
            catch (Exception expected) { rejected=true; System.out.println(kind+": "+expected.getClass().getSimpleName()+" "+expected.getMessage()); }
            check(rejected && Arrays.equals(completed,Files.readAllBytes(target.toPath())),kind+" output not protected");
        }
        check(Arrays.equals(original,Files.readAllBytes(store.original(note.getId()).toPath())) && store.load(note.getId()).equals(saved),"failure changed source");
        System.out.println("PDF_CANCEL_OUTPUT_FAILURE_PROTECTED_DOCUMENT_SOURCE_SAFETY_PASS");
    }
}
