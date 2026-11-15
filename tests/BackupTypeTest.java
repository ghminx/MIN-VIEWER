import org.minviewer.app.BackupType;
import java.io.*;
import java.util.zip.*;

public class BackupTypeTest {
    private static byte[] archive(String first) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(first));
            zip.write(42);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        for (String first : new String[]{"manifest.json", "metadata.properties", "source", "original.pdf", "note.json", "source.pptx"}) {
            BufferedInputStream input = new BufferedInputStream(new ByteArrayInputStream(archive(first)));
            String type = BackupType.detect(input);
            String expected = first.equals("metadata.properties") || first.equals("source") ? "metadata.properties" : "manifest.json";
            if (!type.equals(expected)) throw new AssertionError("wrong backup type: " + first);
            if (new ZipInputStream(input).getNextEntry() == null) throw new AssertionError("input not reusable");
        }
        try {
            BackupType.detect(new BufferedInputStream(new ByteArrayInputStream(archive("other"))));
            throw new AssertionError("unknown backup accepted");
        } catch (IOException expected) { }
        System.out.println("BACKUP_TYPE_PASS");
    }
}
