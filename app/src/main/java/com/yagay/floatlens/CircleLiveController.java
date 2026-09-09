package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Same-touch-session Circle capture used by the floating icon.
 * FV enters Circle before ACTION_UP and routes the rest of that same pointer stream into its
 * full-screen Circle container. This clean-room controller keeps the original FloatIconView as the
 * event owner and renders a non-touchable full-screen capture layer from the forwarded coordinates.
 */
public final class CircleLiveController {
    private static Session active;

    public static synchronized boolean start(Context c, float x, float y) {
        if (active != null) active.cancel("restart");
        active = new Session(c.getApplicationContext(), x, y);
        active.start();
        return true;
    }

    public static synchronized boolean active() { return active != null; }

    public static synchronized void move(float x, float y) {
        if (active != null) active.move(x, y);
    }

    public static synchronized void finish(float x, float y) {
        Session s = active;
        active = null;
        if (s != null) s.finish(x, y, false);
    }

    public static synchronized void cancel(String reason) {
        Session s = active;
        active = null;
        if (s != null) s.cancel(reason == null ? "cancel" : reason);
    }

    private static final class Session {
        private final Context context;
        private final WindowManager wm;
        private final FloatSettings fs;
        private final List<PointF> points = new ArrayList<>();
        private LiveView overlay;
        private Bitmap screenshot;
        private boolean ended;
        private boolean cancelled;
        private boolean processed;

        Session(Context c, float x, float y) {
            context = c;
            wm = (WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
            fs = new FloatSettings(c);
            points.add(new PointF(x, y));
        }

        void start() {
            FloatService service = FloatService.get();
            if (service != null) {
                service.onCircleCaptureStarted();
                service.setScreenshotHidden(true);
            }
            DiagnosticLog.i(context,"CIRCLE_LIVE","ENTER code="+GestureCode.ENTER_CIRCLE+" x="+Math.round(points.get(0).x)+" y="+Math.round(points.get(0).y));
            captureScreen(this::onScreenshot, this::onCaptureFailure);
        }

        void move(float x, float y) {
            if (ended) return;
            PointF last = points.get(points.size()-1);
            if (Math.abs(last.x-x) < 0.5f && Math.abs(last.y-y) < 0.5f) return;
            points.add(new PointF(x,y));
            if (overlay != null) overlay.setPoints(points);
        }

        void finish(float x, float y, boolean wasCancelled) {
            if (ended) return;
            ended = true;
            cancelled = wasCancelled;
            points.add(new PointF(x,y));
            DiagnosticLog.i(context,"CIRCLE_LIVE","RELEASE code="+GestureCode.CIRCLE_FINISH+" points="+points.size()+" cancelled="+cancelled);
            if (overlay != null) overlay.setPoints(points);
            maybeProcess();
        }

        void cancel(String reason) {
            if (ended && processed) return;
            ended = true;
            cancelled = true;
            closeOverlay();
            restoreIcon();
            FloatService f=FloatService.get();
            if(f!=null)f.onCircleFinished(reason);
            DiagnosticLog.i(context,"CIRCLE_LIVE","CANCEL reason="+reason);
        }

        private void onScreenshot(Bitmap b) {
            if (cancelled) { if (b != null) b.recycle(); return; }
            screenshot = b;
            if (!ended) showOverlay();
            maybeProcess();
        }

        private void onCaptureFailure(Throwable t) {
            closeOverlay();
            restoreIcon();
            FloatService f=FloatService.get();
            if(f!=null)f.onCircleFinished("capture_failed");
            Toast.makeText(context,"圈选截图失败: "+safe(t),Toast.LENGTH_LONG).show();
            DiagnosticLog.i(context,"CIRCLE_LIVE","capture failed="+safe(t));
            processed=true;
        }

        private void captureScreen(Consumer<Bitmap> ok, Consumer<Throwable> fail) {
            LensAccessibilityService a=LensAccessibilityService.get();
            if(fs.accessibilityScreenshot() && a!=null){
                a.capture(ok, accessError -> {
                    if(fs.rootScreenshot())RootCapture.captureAsync(context,ok,rootError->fail.accept(rootError));
                    else fail.accept(accessError);
                });
                return;
            }
            if(fs.rootScreenshot()){
                RootCapture.captureAsync(context,ok,rootError->{
                    if(a!=null)a.capture(ok,fail); else fail.accept(rootError);
                });
                return;
            }
            if(a!=null)a.capture(ok,fail);
            else fail.accept(new IllegalStateException("需要开启 FloatLens 无障碍截图或 Root 截图"));
        }

        private void showOverlay() {
            if (overlay != null || screenshot == null || cancelled) return;
            overlay = new LiveView(context, screenshot, points);
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity=Gravity.TOP|Gravity.START;
            try{wm.addView(overlay,lp);}catch(Throwable t){overlay=null;DiagnosticLog.i(context,"CIRCLE_LIVE","overlay add failed="+t);}
        }

        private void maybeProcess() {
            if(processed || !ended || screenshot==null) return;
            processed=true;
            closeOverlay();
            if(cancelled){restoreIcon();return;}
            Bitmap masked=createMaskedCrop();
            restoreIcon();
            if(masked==null){
                FloatService f=FloatService.get();if(f!=null)f.onCircleFinished("selection_too_small");
                return;
            }
            FloatService f=FloatService.get();if(f!=null)f.onCircleRecognizeStarted();
            OcrEngine.recognize(context,masked);
        }

        private Bitmap createMaskedCrop() {
            if(points.size()<2||screenshot==null)return null;
            Rect display=wm.getCurrentWindowMetrics().getBounds();
            float sx=screenshot.getWidth()/(float)Math.max(1,display.width());
            float sy=screenshot.getHeight()/(float)Math.max(1,display.height());
            float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE;
            for(PointF p:points){minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);}
            if(maxX-minX<dp(8)||maxY-minY<dp(8))return null;
            int x=Math.max(0,Math.min(screenshot.getWidth()-1,Math.round(minX*sx)));
            int y=Math.max(0,Math.min(screenshot.getHeight()-1,Math.round(minY*sy)));
            int right=Math.max(x+1,Math.min(screenshot.getWidth(),Math.round(maxX*sx)));
            int bottom=Math.max(y+1,Math.min(screenshot.getHeight(),Math.round(maxY*sy)));
            int w=right-x,h=bottom-y;
            if(w<=1||h<=1)return null;
            Bitmap crop=Bitmap.createBitmap(screenshot,x,y,w,h);
            Bitmap masked=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(masked);
            canvas.drawColor(Color.WHITE);
            Path path=new Path();
            boolean first=true;
            for(PointF p:points){
                float px=p.x*sx-x,py=p.y*sy-y;
                if(first){path.moveTo(px,py);first=false;}else path.lineTo(px,py);
            }
            path.close();
            canvas.save();
            canvas.clipPath(path);
            canvas.drawBitmap(crop,0,0,null);
            canvas.restore();
            crop.recycle();
            DiagnosticLog.i(context,"CIRCLE_LIVE","masked crop="+w+"x"+h+" sourcePoints="+points.size());
            return masked;
        }

        private void closeOverlay(){if(overlay!=null)try{wm.removeView(overlay);}catch(Throwable ignored){}overlay=null;}
        private void restoreIcon(){FloatService service=FloatService.get();if(service!=null)service.setScreenshotHidden(false);}
        private float dp(float v){return v*context.getResources().getDisplayMetrics().density;}
        private String safe(Throwable t){if(t==null)return "unknown";String m=t.getMessage();return m==null||m.isBlank()?t.getClass().getSimpleName():m;}
    }

    private static final class LiveView extends View {
        private final Bitmap screenshot;
        private final Paint bitmapPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<PointF> points;

        LiveView(Context c,Bitmap b,List<PointF> p){
            super(c);screenshot=b;points=new ArrayList<>(p);
            shade.setColor(0x66000000);shade.setStyle(Paint.Style.FILL);
            line.setColor(Color.WHITE);line.setStyle(Paint.Style.STROKE);line.setStrokeWidth(dp(3));
            text.setColor(Color.WHITE);text.setTextSize(dp(15));text.setShadowLayer(dp(3),0,dp(1),Color.BLACK);
        }

        void setPoints(List<PointF> p){points=new ArrayList<>(p);invalidate();}

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            c.drawBitmap(screenshot,null,new Rect(0,0,getWidth(),getHeight()),bitmapPaint);
            c.drawRect(0,0,getWidth(),getHeight(),shade);
            if(points!=null&&!points.isEmpty()){
                Path path=new Path();boolean first=true;
                for(PointF p:points){if(first){path.moveTo(p.x,p.y);first=false;}else path.lineTo(p.x,p.y);}
                c.drawPath(path,line);
            }
            c.drawText("圈选内容 · 松手识别",dp(18),dp(38),text);
        }
        private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    }

    private CircleLiveController(){}
}
