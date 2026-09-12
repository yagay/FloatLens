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

/** Owns icon artwork/resource decoding and slideshow timing; touch semantics stay in FloatIconView. */
final class FloatIconRenderer {
    private final View owner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Drawable> slides = new ArrayList<>();
    private FloatSettings settings;
    private Drawable customDrawable;
    private int slideIndex;

    private final Runnable slideRunnable = new Runnable() {
        @Override public void run() {
            if (settings == null || settings.style() != 4 || slides.size() <= 1) return;
            slideIndex = (slideIndex + 1) % slides.size();
            owner.invalidate();
            main.postDelayed(this, settings.slideIntervalMs());
        }
    };

    FloatIconRenderer(View owner) {
        this.owner = owner;
    }

    void refresh(FloatSettings settings) {
        this.settings = settings;
        main.removeCallbacks(slideRunnable);
        customDrawable = null;
        slides.clear();
        slideIndex = 0;
        if (settings == null) return;

        if (settings.style() == 3) {
            String raw = settings.customIconUri();
            if (raw == null || raw.isBlank()) return;
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(
                        owner.getContext().getContentResolver(), Uri.parse(raw));
                customDrawable = ImageDecoder.decodeDrawable(src);
                customDrawable.setCallback(owner);
                if (customDrawable instanceof AnimatedImageDrawable animated) animated.start();
            } catch (Throwable ignored) {
                customDrawable = null;
            }
        } else if (settings.style() == 4) {
            String raw = settings.slidePics();
            if (raw == null || raw.isBlank()) return;
            for (String value : raw.split("\\|")) {
                if (value.isBlank()) continue;
                try {
                    Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(
                            owner.getContext().getContentResolver(), Uri.parse(value)));
                    drawable.setCallback(owner);
                    slides.add(drawable);
                } catch (Throwable ignored) {}
            }
            if (slides.size() > 1) main.postDelayed(slideRunnable, settings.slideIntervalMs());
        }
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
        main.removeCallbacksAndMessages(null);
        if (customDrawable instanceof AnimatedImageDrawable animated) {
            try { animated.stop(); } catch (Throwable ignored) {}
        }
        customDrawable = null;
        slides.clear();
    }
}
