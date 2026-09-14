package com.yagay.floatlens;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns icon artwork/resource decoding and slideshow timing; touch semantics stay in FloatIconView. */
final class FloatIconRenderer {
    private static final ExecutorService DECODE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-icon-decode");
        t.setDaemon(true);
        return t;
    });

    private final View owner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Drawable> slides = new ArrayList<>();
    private FloatSettings settings;
    private Drawable customDrawable;
    private int slideIndex;
    private long decodeGeneration;
    private boolean detached;
    private int loadedStyle = Integer.MIN_VALUE;
    private String loadedCustomUri = "";
    private String loadedSlideUris = "";
    private int loadedSlideInterval = -1;

    private final Runnable slideRunnable = new Runnable() {
        @Override public void run() {
            if (detached || settings == null || settings.style() != 4 || slides.size() <= 1) return;
            slideIndex = (slideIndex + 1) % slides.size();
            owner.invalidate();
            main.postDelayed(this, settings.slideIntervalMs());
        }
    };

    FloatIconRenderer(View owner) {
        this.owner = owner;
    }

    void refresh(FloatSettings next) {
        settings = next;
        if (detached || next == null) {
            if (next == null) clearArtwork();
            return;
        }

        int style = next.style();
        String customUri = safe(next.customIconUri());
        String slideUris = safe(next.slidePics());
        int slideInterval = next.slideIntervalMs();

        boolean sameArtwork = style == loadedStyle
                && Objects.equals(customUri, loadedCustomUri)
                && Objects.equals(slideUris, loadedSlideUris);
        if (sameArtwork) {
            if (style == 4 && loadedSlideInterval != slideInterval) {
                loadedSlideInterval = slideInterval;
                restartSlideshow();
            }
            return;
        }

        long generation = ++decodeGeneration;
        main.removeCallbacks(slideRunnable);
        clearArtwork();
        loadedStyle = style;
        loadedCustomUri = customUri;
        loadedSlideUris = slideUris;
        loadedSlideInterval = slideInterval;
        slideIndex = 0;

        if (style == 3 && !customUri.isBlank()) {
            int targetPx = targetSizePx(next);
            DECODE_EXECUTOR.execute(() -> decodeCustom(generation, customUri, targetPx));
        } else if (style == 4 && !slideUris.isBlank()) {
            int targetPx = targetSizePx(next);
            DECODE_EXECUTOR.execute(() -> decodeSlides(generation, slideUris, targetPx));
        } else {
            owner.invalidate();
        }
    }

    private void decodeCustom(long generation, String uri, int targetPx) {
        Drawable decoded = null;
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(
                    owner.getContext().getContentResolver(), Uri.parse(uri));
            decoded = ImageDecoder.decodeDrawable(src, (decoder, info, source) -> {
                if (targetPx > 0) decoder.setTargetSize(targetPx, targetPx);
            });
        } catch (Throwable t) {
            DiagnosticLog.i(owner.getContext(), "ICON_RENDER", "custom decode failed=" + t);
        }
        final Drawable result = decoded;
        main.post(() -> {
            if (detached || generation != decodeGeneration) {
                stopAnimated(result);
                return;
            }
            customDrawable = result;
            if (customDrawable != null) {
                customDrawable.setCallback(owner);
                if (customDrawable instanceof AnimatedImageDrawable animated) animated.start();
            }
            owner.invalidate();
        });
    }

    private void decodeSlides(long generation, String raw, int targetPx) {
        ArrayList<Drawable> decoded = new ArrayList<>();
        for (String value : raw.split("\\|")) {
            if (value.isBlank() || generation != decodeGeneration || detached) continue;
            try {
                Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(
                                owner.getContext().getContentResolver(), Uri.parse(value)),
                        (decoder, info, source) -> {
                            if (targetPx > 0) decoder.setTargetSize(targetPx, targetPx);
                        });
                decoded.add(drawable);
            } catch (Throwable t) {
                DiagnosticLog.i(owner.getContext(), "ICON_RENDER", "slide decode failed=" + t);
            }
        }
        main.post(() -> {
            if (detached || generation != decodeGeneration) {
                for (Drawable drawable : decoded) stopAnimated(drawable);
                return;
            }
            slides.clear();
            for (Drawable drawable : decoded) {
                drawable.setCallback(owner);
                slides.add(drawable);
            }
            slideIndex = 0;
            restartSlideshow();
            owner.invalidate();
        });
    }

    private int targetSizePx(FloatSettings value) {
        return Math.max(1, Math.round(value.sizeDp()
                * owner.getResources().getDisplayMetrics().density));
    }

    private void restartSlideshow() {
        main.removeCallbacks(slideRunnable);
        slideIndex = Math.min(slideIndex, Math.max(0, slides.size() - 1));
        if (!detached && settings != null && settings.style() == 4 && slides.size() > 1) {
            main.postDelayed(slideRunnable, settings.slideIntervalMs());
        }
        owner.invalidate();
    }

    private void clearArtwork() {
        main.removeCallbacks(slideRunnable);
        stopAnimated(customDrawable);
        customDrawable = null;
        for (Drawable drawable : slides) stopAnimated(drawable);
        slides.clear();
        slideIndex = 0;
    }

    private void stopAnimated(Drawable drawable) {
        if (drawable instanceof AnimatedImageDrawable animated) {
            try { animated.stop(); } catch (Throwable ignored) {}
        }
        if (drawable != null) drawable.setCallback(null);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    void draw(Canvas canvas, int width, int height, boolean pressed) {
        if (settings == null) return;
        int style = settings.style();
        if (style == 3 && customDrawable != null) {
            customDrawable.setBounds(0, 0, width, height);
            customDrawable.draw(canvas);
            return;
        }
        if (style == 4 && !slides.isEmpty()) {
            Drawable drawable = slides.get(Math.min(slideIndex, slides.size() - 1));
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            return;
        }

        float w = width;
        float h = height;
        float r = Math.min(w, h) * .47f;
        if (pressed) r *= .90f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(style == 1 ? 0xEE202124 : style == 2 ? 0xCCFFFFFF : 0xDD1976D2);
        canvas.drawCircle(w / 2f, h / 2f, r, paint);
        paint.setStrokeWidth(Math.max(3f, w * .07f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(style == 2 ? 0xFF1976D2 : Color.WHITE);
        canvas.drawCircle(w / 2f, h / 2f, r * .53f, paint);
        canvas.drawLine(w * .68f, h * .68f, w * .83f, h * .83f, paint);
    }

    void detach() {
        detached = true;
        decodeGeneration++;
        main.removeCallbacksAndMessages(null);
        clearArtwork();
    }
}
