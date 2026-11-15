package org.minviewer.app;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.FilterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class BackupType {
    private BackupType() { }

    public static String detect(BufferedInputStream input) throws IOException {
        input.mark(131072);
        ZipEntry entry;
        try (ZipInputStream zip = new ZipInputStream(new FilterInputStream(input) {
            @Override public void close() { }
        })) { entry = zip.getNextEntry(); }
        input.reset();
        if (entry == null || entry.isDirectory()) throw new IOException("백업 형식을 확인할 수 없습니다.");
        String name = entry.getName();
        if (name.equals("metadata.properties") || name.equals("source")) return "metadata.properties";
        if (name.equals("manifest.json") || name.equals("original.pdf") || name.equals("note.json") ||
                name.equals("source.pptx") || name.equals("source.docx") || name.equals("source.hwp") || name.equals("source.hwpx")) return "manifest.json";
        throw new IOException("지원하지 않는 백업 형식입니다.");
    }
}
