package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class LensAccessibilityService extends AccessibilityService {
    private static volatile LensAccessibilityService s;
    private volatile EnvironmentState env = new EnvironmentState("", false, 0, true, false, false);

    @Override protected void onServiceConnected(){ s=this; publishEnvironment(); }
    @Override public void onDestroy(){ if(s==this)s=null; super.onDestroy(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        String top = env.topPackage();
        if (e != null && e.getPackageName() != null) {
            String pkg=e.getPackageName().toString();
            if (!pkg.equals(getPackageName()) && !pkg.equals("com.android.systemui")) top=pkg;
        }
        env = inspect(top);
        publishEnvironment();
    }

    private EnvironmentState inspect(String top) {
        boolean ime=false, status=false, shade=false; int imeTop=0;
        int screenH=1;
        try { screenH=((WindowManager)getSystemService(WINDOW_SERVICE)).getCurrentWindowMetrics().getBounds().height(); } catch(Throwable ignored) {}
        try {
            List<AccessibilityWindowInfo> windows=getWindows();
            if (windows!=null) for (AccessibilityWindowInfo w:windows) {
                if (w==null) continue;
                Rect r=new Rect(); w.getBoundsInScreen(r);
                String pkg="";
                try { AccessibilityNodeInfo root=w.getRoot(); if(root!=null&&root.getPackageName()!=null) pkg=root.getPackageName().toString(); } catch(Throwable ignored) {}
                if (w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD) { ime=true; if(imeTop==0||r.top<imeTop) imeTop=r.top; }
                if ("com.android.systemui".equals(pkg)) {
                    if (r.height() <= Math.max(200, screenH/5) && r.top <= screenH/10) status=true;
                    if ((w.isActive() || w.isFocused()) && r.height() > screenH*0.45f) shade=true;
                }
            }
        } catch(Throwable ignored) {}
        boolean fullscreen=!status && !shade && top!=null && !top.isBlank();
        return new EnvironmentState(top==null?"":top, ime, imeTop, status, shade, fullscreen);
    }

    private void publishEnvironment() { FloatService f=FloatService.get(); if(f!=null) f.onAccessibilityEnvironment(env); }
    @Override public void onInterrupt() {}
    public static LensAccessibilityService get(){ return s; }
    public static boolean ready(){ return s!=null; }
    public boolean global(int action){ return performGlobalAction(action); }
    public EnvironmentState environment(){ return env; }

    public boolean tap(float x,float y){
        try {
            Path p=new Path(); p.moveTo(x,y);
            GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,50)).build();
            return dispatchGesture(g,null,null);
        } catch(Throwable t){ return false; }
    }

    public void capture(Consumer<Bitmap> ok, Consumer<Throwable> fail){
        Executor ex=getMainExecutor();
        takeScreenshot(Display.DEFAULT_DISPLAY,ex,new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult r){
                try(HardwareBuffer hb=r.getHardwareBuffer()){
                    Bitmap hw=Bitmap.wrapHardwareBuffer(hb,r.getColorSpace());
                    if(hw==null)throw new IllegalStateException("wrapHardwareBuffer returned null");
                    ok.accept(hw.copy(Bitmap.Config.ARGB_8888,false));
                } catch(Throwable t){fail.accept(t);}
            }
            @Override public void onFailure(int errorCode){ fail.accept(new IllegalStateException("takeScreenshot error="+errorCode)); }
        });
    }
}
