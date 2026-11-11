package org.minviewer.sender;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;

public class ReleaseGrantInstrumentation extends Instrumentation {
    private String targetUri;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        targetUri = arguments.getString("uri");
        start();
    }

    @Override public void onStart() {
        Uri uri = Uri.parse(targetUri);
        boolean found = false;
        for (UriPermission permission : getTargetContext().getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri) && permission.isReadPermission()) found = true;
        }
        Bundle result = new Bundle();
        if (!found) {
            result.putString("result", "persisted grant missing");
            finish(1, result);
            return;
        }
        getTargetContext().getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        result.putString("result", "released");
        finish(0, result);
    }
}
