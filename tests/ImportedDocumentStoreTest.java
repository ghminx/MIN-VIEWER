import org.minviewer.app.ImportedDocumentStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ImportedDocumentStoreTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static byte[] bytes(String s) throws Exception { return s.getBytes("UTF-8"); }
    public static void main(String[] args) throws Exception {
        File root=Files.createTempDirectory("min-import-test").toFile();
        ImportedDocumentStore store=new ImportedDocumentStore(root);
        ImportedDocumentStore.Prepared first=store.prepare(new ByteArrayInputStream(bytes("original")), "report.pdf", ()->false);
        check(first.matches.isEmpty(),"first file must not match");
        ImportedDocumentStore.Entry entry=store.commit(first);
        check(Arrays.equals(bytes("original"),Files.readAllBytes(store.file(entry.id).toPath())),"stored content changed");
        ImportedDocumentStore.Prepared same=store.prepare(new ByteArrayInputStream(bytes("original")), "another.pdf", ()->false);
        check(same.matches.size()==1 && same.matches.get(0).id.equals(entry.id),"identical content must match across names");
        store.discard(same);
        ImportedDocumentStore.Prepared changed=store.prepare(new ByteArrayInputStream(bytes("changed")), "report.pdf", ()->false);
        check(changed.matches.isEmpty(),"same name with different content must not match");
        store.discard(changed);
        InputStream broken=new InputStream(){ int n; public int read() throws IOException {if(n++>3)throw new IOException("source failure");return 1;} };
        try {store.prepare(broken,"broken.pdf",()->false);throw new AssertionError("broken input accepted");} catch(IOException expected) {}
        check(store.all().size()==1 && Arrays.equals(bytes("original"),Files.readAllBytes(store.file(entry.id).toPath())),"failure damaged existing document");
        try {store.prepare(new ByteArrayInputStream(bytes("cancel")),"cancel.pdf",()->true);throw new AssertionError("cancellation accepted");} catch(InterruptedIOException expected) {}
        check(store.all().size()==1,"cancel published a document");
        ByteArrayOutputStream backup=new ByteArrayOutputStream();store.backup(entry.id,backup,()->false);
        ByteArrayOutputStream foreign=new ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(foreign)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("metadata.properties"));zip.write(bytes("name=report.pdf\nsha256="+entry.sha256+"\nbytes=8\n"));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("source"));zip.write(bytes("original"));zip.closeEntry();
        }
        try {store.restore(new ByteArrayInputStream(foreign.toByteArray()),()->false);throw new AssertionError("unidentified backup accepted");} catch(IOException | IllegalArgumentException expected) {}
        ByteArrayOutputStream forged=new ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(forged)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("metadata.properties"));zip.write(bytes("format=MIN_VIEWER_DOCUMENT\nversion=1\nname=report.pdf\nsha256="+entry.sha256+"\nbytes=8\nnoteId="+UUID.randomUUID()+"\n"));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("source"));zip.write(bytes("original"));zip.closeEntry();
        }
        try {store.restore(new ByteArrayInputStream(forged.toByteArray()),()->false);throw new AssertionError("external note link accepted");} catch(IOException | IllegalArgumentException expected) {}
        byte[] invalid=backup.toByteArray().clone();invalid[invalid.length/2]^=127;
        try {store.restore(new ByteArrayInputStream(invalid),()->false);throw new AssertionError("corrupt backup accepted");} catch(IOException | IllegalArgumentException expected) {}
        check(store.all().size()==1 && Arrays.equals(bytes("original"),Files.readAllBytes(store.file(entry.id).toPath())),"corrupt restore damaged existing document");
        ImportedDocumentStore.Entry restored=store.restore(new ByteArrayInputStream(backup.toByteArray()),()->false);
        check(!restored.id.equals(entry.id) && restored.name.equals(entry.name),"restore must create separate item");
        check(Arrays.equals(bytes("original"),Files.readAllBytes(store.file(restored.id).toPath())),"restored content changed");
        String noteId=UUID.randomUUID().toString();
        store.attachNote(entry.id,noteId);
        check(noteId.equals(store.get(entry.id).noteId),"annotation link not persisted");
        check(Arrays.equals(bytes("original"),Files.readAllBytes(store.file(entry.id).toPath())),"link changed source");
        try {store.backup(entry.id,new ByteArrayOutputStream(),()->false);throw new AssertionError("linked note backup omitted ink");} catch(IOException expected) {}
        store.delete(restored.id);
        check(store.all().size()==1 && Arrays.equals(bytes("original"),Files.readAllBytes(store.file(entry.id).toPath())),"deleting copy damaged other document");
        File corruptRoot=Files.createTempDirectory("min-import-corrupt").toFile();
        ImportedDocumentStore corruptStore=new ImportedDocumentStore(corruptRoot);
        ImportedDocumentStore.Entry corruptEntry=corruptStore.commit(corruptStore.prepare(new ByteArrayInputStream(bytes("data")),"safe.pdf",()->false));
        File metadata=new File(new File(corruptRoot,corruptEntry.id),"metadata.properties");
        Properties bad=new Properties();try(InputStream in=new FileInputStream(metadata)){bad.load(in);}
        bad.setProperty("name","bad.extension");try(OutputStream out=new FileOutputStream(metadata)){bad.store(out,null);}
        try {corruptStore.all();throw new AssertionError("corrupt metadata accepted");} catch(IOException expected) {}
        System.out.println("IMPORT_STORE_ROUNDTRIP_DUPLICATE_FAILURE_PASS");
    }
}
