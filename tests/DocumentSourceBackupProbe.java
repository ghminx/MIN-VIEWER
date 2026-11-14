import org.minviewer.app.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DocumentSourceBackupProbe {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        File root=new File(args[0],"documents-"+UUID.randomUUID());
        PdfInkStore store=new PdfInkStore(root);
        File source=new File(args[1]),pdf=new File(args[2]);
        byte[] sourceBytes=Files.readAllBytes(source.toPath());
        for(String format:new String[]{"docx","hwp","hwpx"}) {
            PdfInkNote note=store.createDocument(source,pdf,"original."+format,1,"verified-engine",()->false);
            PdfInkNote saved=new PdfInkNote(note.getId(),note.getName(),1,Collections.singletonList(new InkStroke(0,.004f,0xffff0000,
                Arrays.asList(new InkPoint(.2f,.3f,.7f,100),new InkPoint(.6f,.5f,.9f,150)))));
            store.save(saved);
            ByteArrayOutputStream out=new ByteArrayOutputStream();store.backup(note.getId(),out);
            Map<String,byte[]> files=InkBackupProbe.unpack(out.toByteArray());
            check(files.keySet().equals(new HashSet<>(Arrays.asList("manifest.json","original.pdf","note.json","source."+format))),"document backup entries");
            JSONObject manifest=new JSONObject(new String(files.get("manifest.json"),StandardCharsets.UTF_8));
            check(manifest.getInt("version")==3&&manifest.getJSONObject("document").getString("format").equals(format),"document profile missing");
            check(Arrays.equals(sourceBytes,files.get("source."+format)),"source changed");
            PdfInkNote restored=store.restore(new ByteArrayInputStream(out.toByteArray()));
            check(restored.getStrokes().equals(saved.getStrokes())&&restored.getName().equals(saved.getName()),"restore lost ink or name");
            int count=store.all().size();files.get("source."+format)[0]^=1;
            boolean rejected=false;try{store.restore(new ByteArrayInputStream(InkBackupProbe.pack(files)));}catch(Exception expected){rejected=true;}
            check(rejected&&store.all().size()==count&&store.load(saved.getId()).equals(saved),"corrupt source damaged notes");
            for(String bad:new String[]{"missing","version","extra"}) {
                Map<String,byte[]> altered=InkBackupProbe.unpack(out.toByteArray());
                if(bad.equals("missing"))altered.remove("source."+format);
                else if(bad.equals("version")) {
                    JSONObject wrong=new JSONObject(new String(altered.get("manifest.json"),StandardCharsets.UTF_8));wrong.put("version",2);
                    altered.put("manifest.json",wrong.toString().getBytes(StandardCharsets.UTF_8));
                } else altered.put("source.pptx",sourceBytes);
                rejected=false;try{store.restore(new ByteArrayInputStream(InkBackupProbe.pack(altered)));}catch(Exception expected){rejected=true;}
                check(rejected&&store.all().size()==count,"invalid document archive was published: "+bad);
            }
            boolean canceled=false;
            try{store.createDocument(source,pdf,"cancel."+format,1,"verified-engine",()->true);}catch(Exception expected){check(expected instanceof InterruptedIOException,"wrong cancellation");canceled=true;}
            check(canceled&&store.all().size()==count,"canceled creation was published");
            System.out.println(format+"_SOURCE_SNAPSHOT_INK_BACKUP_RESTORE_CORRUPTION_PASS");
        }
    }
}
