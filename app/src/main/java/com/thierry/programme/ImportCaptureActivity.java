package com.thierry.programme;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * v6.3 — Reçoit une capture partagée depuis Xiaomi Home (ACTION_SEND image/*).
 * Copie l'image dans le cache de l'appli (l'accès à l'URI partagée est temporaire),
 * puis ouvre MainActivity sur l'onglet Poids qui lance la lecture OCR.
 */
public class ImportCaptureActivity extends Activity {
    public static final String ACTION_IMPORT = "com.thierry.programme.IMPORT_CAPTURE";
    public static final String EXTRA_PATH = "capture_path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Uri uri = getSharedUri(getIntent());
        if (uri == null) {
            Toast.makeText(this, "Aucune image reçue", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        new Thread(() -> {
            File out = new File(getCacheDir(), "s400_shared.img");
            boolean ok = false;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(out)) {
                if (in != null) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    ok = true;
                }
            } catch (Exception ignored) { }
            final boolean copied = ok;
            runOnUiThread(() -> {
                if (copied) {
                    Intent i = new Intent(this, MainActivity.class);
                    i.setAction(ACTION_IMPORT);
                    i.putExtra(EXTRA_PATH, out.getAbsolutePath());
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(i);
                } else {
                    Toast.makeText(this, "Impossible de lire la capture", Toast.LENGTH_LONG).show();
                }
                finish();
            });
        }).start();
    }

    @SuppressWarnings("deprecation")
    private Uri getSharedUri(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        Uri u;
        if (Build.VERSION.SDK_INT >= 33) u = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        else u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0)
            u = intent.getClipData().getItemAt(0).getUri();
        return u;
    }
}
