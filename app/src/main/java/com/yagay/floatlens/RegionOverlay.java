package com.yagay.floatlens;

import android.content.Context;
import android.graphics.*;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** Region screenshot selector; OCR mode uses a free-form lasso/circle path. */
public final class RegionOverlay {
    public static void show(Context c, Bitmap screen, boolean ocr) {
        WindowManager wm=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
        SelectView v=new SelectView(c,screen,ocr,wm);
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START; wm.addView(v,lp);
    }
    static class SelectView extends View {
        final Bitmap b; final boolean ocr; final WindowManager wm; float sx,sy,ex,ey; final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG), textPaint=new Paint(Paint.ANTI_ALIAS_FLAG); boolean selecting; final Path lasso=new Path(); final List<PointF> pts=new ArrayList<>();
        SelectView(Context c,Bitmap b,boolean ocr,WindowManager wm){super(c);this.b=b;this.ocr=ocr;this.wm=wm;p.setStrokeWidth(dp(2));textPaint.setColor(Color.WHITE);textPaint.setTextSize(dp(16));textPaint.setShadowLayer(dp(3),0,dp(1),Color.BLACK);}
        @Override protected void onDraw(Canvas c){c.drawBitmap(b,null,new Rect(0,0,getWidth(),getHeight()),p);p.setStyle(Paint.Style.FILL);p.setColor(0x77000000);c.drawRect(0,0,getWidth(),getHeight(),p);if(selecting){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));p.setColor(Color.WHITE);if(ocr)c.drawPath(lasso,p);else c.drawRect(rect(),p);}String tip=ocr?"圈选要识别的内容 · 松手开始 OCR":"拖动选择截图区域";c.drawText(tip,dp(18),dp(38),textPaint);}
        @Override public boolean onTouchEvent(MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN->{sx=ex=e.getX();sy=ey=e.getY();selecting=true;pts.clear();lasso.reset();lasso.moveTo(sx,sy);pts.add(new PointF(sx,sy));invalidate();return true;}case MotionEvent.ACTION_MOVE->{ex=e.getX();ey=e.getY();if(ocr){lasso.lineTo(ex,ey);pts.add(new PointF(ex,ey));}invalidate();return true;}case MotionEvent.ACTION_UP->{ex=e.getX();ey=e.getY();if(ocr){lasso.lineTo(ex,ey);lasso.close();pts.add(new PointF(ex,ey));}finishSelection();return true;}case MotionEvent.ACTION_CANCEL->{close();return true;}}return true;}
        private RectF rect(){return new RectF(Math.min(sx,ex),Math.min(sy,ey),Math.max(sx,ex),Math.max(sy,ey));}
        private RectF lassoBounds(){RectF r=new RectF();lasso.computeBounds(r,true);return r;}
        private void finishSelection(){RectF r=ocr?lassoBounds():rect();close();if(r.width()<dp(8)||r.height()<dp(8))return;float xs=b.getWidth()/(float)getWidth(),ys=b.getHeight()/(float)getHeight();int x=Math.max(0,Math.round(r.left*xs)),y=Math.max(0,Math.round(r.top*ys));int w=Math.min(b.getWidth()-x,Math.max(1,Math.round(r.width()*xs))),h=Math.min(b.getHeight()-y,Math.max(1,Math.round(r.height()*ys)));try{Bitmap crop=Bitmap.createBitmap(b,x,y,w,h);if(ocr){Bitmap masked=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas mc=new Canvas(masked);mc.drawColor(Color.WHITE);Path path=new Path();boolean first=true;for(PointF q:pts){float px=q.x*xs-x,py=q.y*ys-y;if(first){path.moveTo(px,py);first=false;}else path.lineTo(px,py);}path.close();mc.save();mc.clipPath(path);mc.drawBitmap(crop,0,0,null);mc.restore();OcrEngine.recognize(getContext(),masked);}else ScreenshotController.save(getContext(),crop);}catch(Throwable t){Toast.makeText(getContext(),"区域处理失败: "+t.getMessage(),Toast.LENGTH_LONG).show();}}
        private void close(){try{wm.removeView(this);}catch(Throwable ignored){}} private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    }
    private RegionOverlay(){}
}
