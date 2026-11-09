package org.minviewer.sender;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class SenderActivity extends Activity {
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        try (FileOutputStream output = openFileOutput("sample.csv", MODE_PRIVATE)) {
            output.write("name,note\nAlice,shared file\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        String name = getIntent().getStringExtra("name");
        if (name == null) name = "sample.csv";
        Uri uri = Uri.parse("content://org.minviewer.sender.files/" + name);
        String mime = TestProvider.mimeType(name);
        String action = getIntent().getStringExtra("action");
        Intent outgoing = new Intent("send".equals(action) ? Intent.ACTION_SEND : Intent.ACTION_VIEW);
        if ("send".equals(action)) {
            outgoing.setType(mime);
            if (!getIntent().getBooleanExtra("clipOnly", false)) outgoing.putExtra(Intent.EXTRA_STREAM, uri);
        } else {
            outgoing.setDataAndType(uri, mime);
        }
        outgoing.setPackage("org.minviewer.app");
        if (getIntent().getBooleanExtra("grant", true)) {
            outgoing.setClipData(ClipData.newRawUri("file", uri));
            outgoing.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        startActivity(outgoing);
        finish();
    }
}
