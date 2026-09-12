package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.view.HapticFeedbackConstants;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;

/** Shared image actions for every result popup. */
public final class ImageShareUtils {
    private static final String DIR = "shared_images";

    /**
     * Long-press no longer launches sharing immediately. It opens the reusable FloatLens image
     * action menu so Activity-backed result windows and TYPE_APPLICATION_OVERLAY fallbacks behave
     * the same way.
     */
    public static void attachLongPressMenu(Context c, ImageView view, Bitmap image) {
        if (c == null || view == null || image == null || image.isRecycled()) return;
        view.setLongClickable(true);
        view.setOnLongClickListener(v -> {
            try { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); }
            catch (Throwable ignored) {}
            Rect anchor = FloatMenuAnchor.forView(v);
            ImageActionMenu.show(c, image, anchor);
            return true;
        });
    }

    /** Compatibility alias: all existing callers now get the long-press menu automatically. */
    public static void attachLongPressShare(Context c, ImageView view, Bitmap image) {
        attachLongPressMenu(c, view, image);
    }

    /** Put the exact popup bitmap on Android's clipboard as an image content URI. */
    public static boolean copyToClipboard(Context c, Bitmap image) {
        if (c == null || image == null || image.isRecycled()) return false;
        Context app = c.getApplicationContext();
        try {
            SharedImage shared = prepareSharedImage(app, image);
            ClipboardManager clipboard =
                    (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");

            ClipData clip = ClipData.newUri(
                    app.getContentResolver(), "FloatLens image", shared.uri);
            clipboard.setPrimaryClip(clip);
            DiagnosticLog.i(app, "IMAGE_CLIPBOARD", "COPY " + shared.file.getName()
                    + " " + image.getWidth() + "x" + image.getHeight());
            Toast.makeText(app, "已复制图片", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(app, "IMAGE_CLIPBOARD", "FAILED " + t);
            Toast.makeText(app, "无法复制图片", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    public static void share(Context c, Bitmap image) {
        if (c == null || image == null || image.isRecycled()) return;
        Context app = c.getApplicationContext();
        try {
            SharedImage shared = prepareSharedImage(app, image);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, shared.uri);
            send.setClipData(ClipData.newRawUri("FloatLens image", shared.uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(send, "分享图片")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            app.startActivity(chooser);
            DiagnosticLog.i(app, "IMAGE_SHARE", "OPEN " + shared.file.getName()
                    + " " + image.getWidth() + "x" + image.getHeight());
        } catch (Throwable t) {
            DiagnosticLog.i(app, "IMAGE_SHARE", "FAILED " + t);
            Toast.makeText(app, "无法分享图片", Toast.LENGTH_SHORT).show();
        }
    }

    /** Open the current popup bitmap with any installed app that accepts image/png. */
    public static void openWith(Context c, Bitmap image) {
        if (c == null || image == null || image.isRecycled()) return;
        Context app = c.getApplicationContext();
        try {
            SharedImage shared = prepareSharedImage(app, image);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(shared.uri, "image/png");
            // Intent#setClipData returns void on Android, so keep the permission payload separate
            // instead of chaining it after setDataAndType().
            view.setClipData(ClipData.newRawUri("FloatLens image", shared.uri));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(view, "打开方式");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            app.startActivity(chooser);
            DiagnosticLog.i(app, "IMAGE_OPEN_WITH", "OPEN " + shared.file.getName()
                    + " " + image.getWidth() + "x" + image.getHeight());
        } catch (Throwable t) {
            DiagnosticLog.i(app, "IMAGE_OPEN_WITH", "FAILED " + t);
            Toast.makeText(app, "没有可用的图片应用", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Clipboard, Share and Open-With must expose the exact same temporary image/URI semantics.
     * Keeping this in one helper also guarantees the FileProvider path stays consistent.
     */
    private static SharedImage prepareSharedImage(Context app, Bitmap image) throws Exception {
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
        return new SharedImage(outFile, uri);
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

    private static final class SharedImage {
        final File file;
        final Uri uri;

        SharedImage(File file, Uri uri) {
            this.file = file;
            this.uri = uri;
        }
    }

    private ImageShareUtils() {}
}
