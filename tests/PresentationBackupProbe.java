import org.minviewer.app.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

public class PresentationBackupProbe {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void reject(PdfInkStore store,PdfInkNote saved,byte[] backup) throws Exception {
        boolean rejected=false;try{store.restore(new ByteArrayInputStream(backup));}catch(Exception expected){rejected=true;}
        check(rejected&&store.all().size()==2&&store.load(saved.getId()).equals(saved),"bad PPT backup damaged existing notes");
    }
    public static void main(String[] args) throws Exception {
        File root=new File(args[0],"ppt-"+UUID.randomUUID());PdfInkStore store=new PdfInkStore(root);
        File source=new File(args[1]),snapshot=new File(args[2]);
        byte[] original=Files.readAllBytes(source.toPath()),pdf=Files.readAllBytes(snapshot.toPath());
        PdfInkNote created=store.createPresentation(source,snapshot,"original.pptx",1,"test-build",()->false);
        PdfInkNote saved=new PdfInkNote(created.getId(),created.getName(),1,Collections.singletonList(new InkStroke(0,.004f,0xffff0000,
            Arrays.asList(new InkPoint(.2f,.3f,.7f,100),new InkPoint(.6f,.5f,.9f,150)))));
        store.save(saved);ByteArrayOutputStream output=new ByteArrayOutputStream();store.backup(saved.getId(),output);
        byte[] valid=output.toByteArray();Map<String,byte[]> files=InkBackupProbe.unpack(valid);
        check(files.keySet().equals(new HashSet<>(Arrays.asList("manifest.json","original.pdf","note.json","source.pptx"))),"PPT backup contents incomplete");
        check(Arrays.equals(original,files.get("source.pptx"))&&Arrays.equals(pdf,files.get("original.pdf")),"backup changed source or background");
        JSONObject manifest=new JSONObject(new String(files.get("manifest.json"),StandardCharsets.UTF_8));
        check(manifest.getInt("version")==2&&manifest.getJSONObject("presentation").getString("engineBuild").equals("test-build"),"PPT profile missing");
        PdfInkNote restored=store.restore(new ByteArrayInputStream(valid));
        check(!restored.getId().equals(saved.getId())&&restored.getName().equals(saved.getName())&&restored.getStrokes().equals(saved.getStrokes()),"PPT restore changed ink");
        ByteArrayOutputStream roundtrip=new ByteArrayOutputStream();store.backup(restored.getId(),roundtrip);
        check(Arrays.equals(original,InkBackupProbe.unpack(roundtrip.toByteArray()).get("source.pptx")),"restored source missing from backup");
        System.out.println("PPT_SOURCE_SNAPSHOT_PROFILE_INK_BACKUP_ROUNDTRIP_PASS");
        files=InkBackupProbe.unpack(valid);files.remove("source.pptx");reject(store,saved,InkBackupProbe.pack(files));
        files=InkBackupProbe.unpack(valid);files.get("source.pptx")[0]^=1;reject(store,saved,InkBackupProbe.pack(files));
        files=InkBackupProbe.unpack(valid);manifest.put("version",1);files.put("manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));reject(store,saved,InkBackupProbe.pack(files));
        files=InkBackupProbe.unpack(valid);manifest.put("version",2);manifest.getJSONObject("presentation").put("policy","unknown");files.put("manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));reject(store,saved,InkBackupProbe.pack(files));
        int count=store.all().size();boolean canceled=false;
        try{store.createPresentation(source,snapshot,"cancel.pptx",1,"test-build",()->true);}catch(Exception expected){check(expected instanceof InterruptedIOException,"wrong cancellation");canceled=true;}
        check(canceled&&store.all().size()==count,"canceled PPT creation published a note");
        boolean mismatch=false;try{store.createPresentation(source,snapshot,"wrong-pages.pptx",2,"test-build",()->false);}catch(IllegalArgumentException expected){mismatch=true;}
        check(mismatch&&store.all().size()==count,"page mismatch published a note");
        check(Arrays.equals(original,Files.readAllBytes(source.toPath()))&&Arrays.equals(pdf,Files.readAllBytes(snapshot.toPath())),"PPT preparation changed inputs");
        System.out.println("PPT_CORRUPT_MISSING_PROFILE_CANCEL_PAGE_MISMATCH_SOURCE_SAFETY_PASS");
    }
}
