package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import java.util.List;

/** Full-screen selector that highlights the accessibility View under the finger. */
public final class ViewSelectionOverlay {
    public static void show(Context c) {
        LensAccessibilityService a=LensAccessibilityService.get();
        if(a==null){
            Toast.makeText(c,"需要开启 FloatLens 无障碍服务，已改用 OCR 圈选",Toast.LENGTH_SHORT).show();
            ScreenshotController.captureForOcr(c);
            return;
        }
        WindowManager wm=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
        PickView v=new PickView(c,a,wm);
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START;
        try{wm.addView(v,lp);}catch(Throwable t){
            Toast.makeText(c,"View 选择层启动失败，改用 OCR",Toast.LENGTH_SHORT).show();
            ScreenshotController.captureForOcr(c);
        }
    }

    private static final class PickView extends View {
        private final LensAccessibilityService a;
        private final WindowManager wm;
        private final Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private ViewNodeCandidate current;
        private long lastScan;
        private boolean touched;

        PickView(Context c,LensAccessibilityService a,WindowManager wm){
            super(c);this.a=a;this.wm=wm;
            border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(dp(2));border.setColor(0xFFFFFFFF);
            fill.setStyle(Paint.Style.FILL);fill.setColor(0x332196F3);
            textPaint.setColor(Color.WHITE);textPaint.setTextSize(dp(15));textPaint.setShadowLayer(dp(3),0,dp(1),Color.BLACK);
            setBackgroundColor(0x22000000);
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            if(current!=null){
                Rect r=current.bounds();c.drawRect(r,fill);c.drawRect(r,border);
                String label=current.label();
                float y=Math.max(dp(24),r.top-dp(8));
                c.drawText(label,Math.max(dp(8),r.left),y,textPaint);
            }else{
                c.drawText("移动手指选择 View · 松手提取文字",dp(18),dp(42),textPaint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e){
            int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE){
                touched=true;
                long now=SystemClock.uptimeMillis();
                if(action==MotionEvent.ACTION_DOWN||now-lastScan>=40){
                    lastScan=now;
                    current=a.findViewAt(e.getRawX(),e.getRawY());
                    if(current!=null) DiagnosticLog.i(getContext(),"VIEW_PICK","bounds="+current.bounds()+" textLen="+current.text().length()+" class="+current.className());
                    invalidate();
                }
                return true;
            }
            if(action==MotionEvent.ACTION_UP){
                finishSelection();return true;
            }
            if(action==MotionEvent.ACTION_CANCEL){close();return true;}
            return true;
        }

        private void finishSelection(){
            ViewNodeCandidate picked=current;
            close();
            if(picked==null){
                ScreenshotController.captureForOcr(getContext());
                return;
            }
            if(picked.hasText()){
                FloatService f=FloatService.get();if(f!=null)f.onOcrResults(1);
                ResultOverlay.show(getContext(),picked.text(), List.of(picked.text()),null);
                return;
            }
            Rect b=picked.bounds();
            if(!b.isEmpty()) ScreenshotController.captureBoundsForOcr(getContext(),b);
            else ScreenshotController.captureForOcr(getContext());
        }
        private void close(){try{wm.removeView(this);}catch(Throwable ignored){}}
        private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    }

    private ViewSelectionOverlay(){}
}
