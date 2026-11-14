import org.minviewer.app.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 실제 용량 부족·프로세스 종료·대량 획 저장의 공개 저장소 계약 검증. */
public class InkStorageStressProbe {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static List<InkStroke> strokes(int count) {
        List<InkStroke> result=new ArrayList<>();
        for(int i=0;i<count;i++) {
            List<InkPoint> points=new ArrayList<>();
            for(int j=0;j<8;j++)points.add(new InkPoint(.1f+j*.08f,.15f+(i%40)*.015f,.7f,i*100L+j));
            result.add(new InkStroke(i%3,.002f,0xffff0000,points));
        }
        return result;
    }
    public static void main(String[] args)throws Exception {
        String mode=args[0];File root=new File(args[1]);PdfInkStore store=new PdfInkStore(root);
        if(mode.equals("seed")) {
            try(InputStream input=new FileInputStream(args[2])) {
                PdfInkNote note=store.create(input,"stress.pdf",3);
                store.save(new PdfInkNote(note.getId(),note.getName(),3,strokes(1)));
                System.out.println(note.getId());
            }
            return;
        }
        String id=args[2];PdfInkNote baseline=store.load(id);
        File ink=new File(root,id+"/note.json");byte[] previous=Files.readAllBytes(ink.toPath());
        if(mode.equals("full")) {
            File fill=new File(root.getParentFile(),"capacity-fill.bin");boolean full=false;
            try(FileOutputStream out=new FileOutputStream(fill)) {
                byte[] block=new byte[4096];
                for(int i=0;i<32768;i++)out.write(block);
            }catch(IOException expected){check(expected.getMessage().contains("ENOSPC"),expected.toString());full=true;}
            check(full,"bounded filesystem did not report ENOSPC");
            try {
                boolean rejected=false;
                try{store.save(new PdfInkNote(id,"updated.pdf",3,strokes(5000)));}catch(Exception expected){check(expected instanceof IOException,expected.toString());rejected=true;}
                check(rejected,"save unexpectedly succeeded on full filesystem");
                check(Arrays.equals(previous,Files.readAllBytes(ink.toPath())),"failed save replaced durable bytes");
                check(store.load(id).equals(baseline),"failed save lost previous ink");
                int count=store.all().size();rejected=false;
                try(InputStream input=new FileInputStream(store.original(id))){store.create(input,"new.pdf",3);}catch(IOException expected){rejected=true;}
                check(rejected&&store.all().size()==count,"full filesystem published incomplete note");
                System.out.println("REAL_ENOSPC_SAVE_CREATE_PREVIOUS_INK_PRESERVED_PASS");
            }finally{check(fill.delete(),"filler cleanup failed");}
            store.save(new PdfInkNote(id,"recovered.pdf",3,strokes(5000)));
            check(store.load(id).getStrokes().size()==5000,"save did not recover after freeing space");
            System.out.println("SPACE_RELEASE_SAVE_RECOVERY_PASS");return;
        }
        if(mode.equals("write")) {
            List<InkStroke> values=strokes(10000);
            for(int cycle=0;cycle<100;cycle++)store.save(new PdfInkNote(id,"cycle-"+cycle+".pdf",3,values));
            return;
        }
        if(mode.equals("verify")) {
            int count=baseline.getStrokes().size();
            check(count==1||count==10000,"partial stroke set after process death: "+count);
            check(count==1||baseline.getStrokes().equals(strokes(10000)),"mixed points after process death");
            System.out.println("PROCESS_DEATH_ATOMIC_RECOVERY_PASS strokes="+count+" bytes="+ink.length());return;
        }
        if(mode.equals("bench")) {
            for(int count:new int[]{1000,5000,10000}) {
                PdfInkNote updated=new PdfInkNote(id,"stress.pdf",3,strokes(count));
                long t=System.nanoTime();store.save(updated);double save=(System.nanoTime()-t)/1e6;
                t=System.nanoTime();PdfInkNote loaded=store.load(id);double load=(System.nanoTime()-t)/1e6;
                check(loaded.equals(updated),"large note restoration changed ink");
                t=System.nanoTime();ByteArrayOutputStream archive=new ByteArrayOutputStream();store.backup(id,archive);
                PdfInkNote restored=store.restore(new ByteArrayInputStream(archive.toByteArray()));double backup=(System.nanoTime()-t)/1e6;
                check(restored.getStrokes().equals(updated.getStrokes()),"large backup lost ink");
                System.out.printf(Locale.ROOT,"BENCH strokes=%d points=%d jsonBytes=%d saveMs=%.1f loadMs=%.1f backupRestoreMs=%.1f%n",count,count*8,ink.length(),save,load,backup);
            }
        }
    }
}
