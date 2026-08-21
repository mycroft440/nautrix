package org.chromium.chrome.browser.nautrix.torrent;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Entry point for magnet links and .torrent documents. */
public final class NautrixTorrentEntryActivity extends Activity {
    private static final long MAX_TORRENT_METADATA_BYTES = 16L * 1024L * 1024L;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Uri data = intent == null ? null : intent.getData();
        if (data != null) {
            if ("magnet".equalsIgnoreCase(data.getScheme())) {
                NautrixTorrentService.enqueueMagnet(this, data.toString());
            } else {
                File local = copyTorrentMetadata(this, data);
                if (local != null) {
                    NautrixTorrentService.enqueueTorrentFile(this, local.getAbsolutePath());
                }
            }
        }
        finish();
    }

    static @Nullable File copyTorrentMetadata(Context context, Uri uri) {
        File dir = new File(context.getFilesDir(), "torrent_metainfo");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File target = new File(dir, "torrent-" + System.nanoTime() + ".torrent");
        long total = 0;
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) return null;
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_TORRENT_METADATA_BYTES) {
                    target.delete();
                    return null;
                }
                out.write(buffer, 0, read);
            }
            out.getFD().sync();
            return target;
        } catch (Exception e) {
            target.delete();
            return null;
        }
    }
}
