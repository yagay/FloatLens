package com.yagay.floatlens;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.HapticFeedbackConstants;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;

/** Shared long-press image sharing for every result popup. */
public final class ImageShareUtils {
    private static final String DIR = "shared_images";

    public static void attachLongPressShare(Context c, ImageView view, Bitmap image) {
        if (c == null || view == null || image == null || image.isRecycled()) return;
        view.setLongClickable(true);
        view.setOnLongClickListener(v -> {
            try { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignored) {}
            share(c, image);
            return true;
        });
    }

    public static void share(Context c, Bitmap image) {
        if (c == null || image == null || image.isRecycled()) return;
        Context app = c.getApplicationContext();
        try {
            File dir = new File(app.getCacheDir(), DIR);
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create share cache");
            cleanupOld(dir);

            File outFile = new File(dir, "FloatLens_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IllegalStateException("Bitmap compress failed");
                }
                out.flush();
            }

            Uri uri = FileProvider.getUriForFile(app,
                    app.getPackageName() + ".fileprovider", outFile);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newRawUri("FloatLens image", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(send, "分享图片")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(chooser);
            DiagnosticLog.i(app, "IMAGE_SHARE", "OPEN " + outFile.getName()
                    + " " + image.getWidth() + "x" + image.getHeight());
        } catch (Throwable t) {
            DiagnosticLog.i(app, "IMAGE_SHARE", "FAILED " + t);
            Toast.makeText(app, "无法分享图片", Toast.LENGTH_SHORT).show();
        }
    }

    private static void cleanupOld(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File f : files) {
            if (f != null && f.isFile() && f.lastModified() < cutoff) {
                try { f.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    private ImageShareUtils() {}
}
