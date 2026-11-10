package org.minviewer.sender;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

public class TestProvider extends ContentProvider {
    private int singleOpenCount = 0;
    @Override public boolean onCreate() { return true; }
    static String mimeType(String name) {
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".hwp")) return "application/x-hwp";
        if (name.endsWith(".hwpx")) return "application/hwp+zip";
        if (name.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "text/csv";
    }
    @Override public String getType(Uri uri) { return mimeType(uri.getLastPathSegment()); }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor result = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME });
        result.addRow(new Object[] { uri.getLastPathSegment() });
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !(name.matches("sample\\.(csv|pdf|hwp|hwpx|pptx|docx|xlsx)") || "pipe.pdf".equals(name) || "single.csv".equals(name)) || !"r".equals(mode)) throw new FileNotFoundException();
        if ("single.csv".equals(name) && singleOpenCount++ > 0) throw new FileNotFoundException("시험 스트림을 다시 열 수 없습니다.");
        File file = new File(getContext().getFilesDir(), name);
        if (!"pipe.pdf".equals(name)) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (FileInputStream input = new FileInputStream(file); OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                } catch (IOException ignored) {
                    // 첫 번째 스트림을 호출자가 일찍 닫는 경우 처리
                }
            }).start();
            return pipe[0];
        } catch (IOException error) {
            throw new FileNotFoundException("스트림 생성 실패");
        }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
