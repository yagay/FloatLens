package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.HardwareBuffer;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class LensAccessibilityService extends AccessibilityService {
    private static volatile LensAccessibilityService s;
    private volatile EnvironmentState env = new EnvironmentState("", false, 0, true, false, false);

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        s=this;
        try {
            AccessibilityServiceInfo info=getServiceInfo();
            if(info!=null){
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                setServiceInfo(info);
            }
        } catch(Throwable t){
            DiagnosticLog.i(this,"ACCESSIBILITY","setServiceInfo flags failed="+t);
        }
        try { publishEnvironment(); }
        catch (Throwable t) { DiagnosticLog.i(this,"ACCESSIBILITY","publish on connect failed="+t); }
    }

    @Override public void onDestroy(){ if(s==this)s=null; super.onDestroy(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            String top = env.topPackage();
            if (e != null && e.getPackageName() != null) {
                String pkg=e.getPackageName().toString();
                if (!pkg.equals(getPackageName()) && !pkg.equals("com.android.systemui")) top=pkg;
            }
            env = inspect(top);
            publishEnvironment();
        } catch (Throwable t) {
            DiagnosticLog.i(this,"ACCESSIBILITY","event failed="+t);
        }
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

    /**
     * Position-only View hit testing.
     *
     * Android returns accessibility windows in descending layer order (top-most first). We walk
     * them in that documented order and first require the actual interactive Region to contain the
     * pointer. Inside a window, selection is purely positional: visible node -> bounds contains ->
     * descend through the child at that position -> most specific node. Near-fullscreen/root
     * containers are retained only as the final fallback.
     */
    public ViewNodeCandidate findViewAt(float x, float y) {
        try {
            final int px=Math.round(x), py=Math.round(y);
            final Rect screen=currentScreenBounds();
            AccessibilityNodeInfo fullscreenFallback=null;

            List<AccessibilityWindowInfo> windows=getWindows();
            if(windows!=null){
                // Official API ordering is descending layer order: index 0 is top-most.
                for(int i=0;i<windows.size();i++){
                    AccessibilityWindowInfo w=windows.get(i);
                    if(w==null || !windowContainsPoint(w,px,py))continue;

                    AccessibilityNodeInfo root=null;
                    try{root=w.getRoot();}catch(Throwable ignored){}
                    if(root==null)continue;
                    String pkg=nodePackage(root);
                    if(getPackageName().equals(pkg))continue;

                    PositionMatch hit=findByPosition(root,px,py,screen,0,new int[]{0});
                    if(hit==null||hit.node==null)continue;
                    if(hit.fullscreenLike){
                        if(fullscreenFallback==null)fullscreenFallback=hit.node;
                        continue;
                    }

                    ViewNodeCandidate out=snapshot(hit.node);
                    DiagnosticLog.i(this,"VIEW_PICK","window="+i+" layer="+safeLayer(w)+" pkg="+pkg+" depth="+hit.depth+" bounds="+out.bounds()+" class="+out.className()+" id="+out.viewId()+" kind="+out.kind());
                    return out;
                }
            }

            // Launcher implementations can expose the workspace more reliably through the active
            // root than through getWindows() while overlays are present. Use it only after all
            // concrete top-layer positional matches above failed.
            try{
                AccessibilityNodeInfo active=getRootInActiveWindow();
                if(active!=null && !getPackageName().equals(nodePackage(active))){
                    PositionMatch hit=findByPosition(active,px,py,screen,0,new int[]{0});
                    if(hit!=null&&hit.node!=null){
                        if(!hit.fullscreenLike){
                            ViewNodeCandidate out=snapshot(hit.node);
                            DiagnosticLog.i(this,"VIEW_PICK","active-root fallback pkg="+nodePackage(active)+" depth="+hit.depth+" bounds="+out.bounds()+" class="+out.className()+" id="+out.viewId()+" kind="+out.kind());
                            return out;
                        }
                        if(fullscreenFallback==null)fullscreenFallback=hit.node;
                    }
                }
            }catch(Throwable t){
                DiagnosticLog.i(this,"VIEW_PICK","active-root fallback failed="+t);
            }

            if(fullscreenFallback!=null){
                ViewNodeCandidate out=snapshot(fullscreenFallback);
                DiagnosticLog.i(this,"VIEW_PICK","fullscreen fallback bounds="+out.bounds()+" class="+out.className()+" id="+out.viewId());
                return out;
            }
            return null;
        } catch(Throwable t){
            DiagnosticLog.i(this,"VIEW_PICK","findViewAt failed="+t);
            return null;
        }
    }

    private boolean windowContainsPoint(AccessibilityWindowInfo w,int x,int y){
        try{
            Region region=new Region();
            w.getRegionInScreen(region);
            if(!region.isEmpty())return region.contains(x,y);
        }catch(Throwable ignored){}
        try{
            Rect r=new Rect();
            w.getBoundsInScreen(r);
            return !r.isEmpty()&&r.contains(x,y);
        }catch(Throwable t){return false;}
    }

    private int safeLayer(AccessibilityWindowInfo w){
        try{return w.getLayer();}catch(Throwable t){return Integer.MIN_VALUE;}
    }

    private String nodePackage(AccessibilityNodeInfo n){
        try{return n!=null&&n.getPackageName()!=null?n.getPackageName().toString():"";}
        catch(Throwable t){return "";}
    }

    private static final class PositionMatch {
        final AccessibilityNodeInfo node;
        final boolean fullscreenLike;
        final int depth;
        PositionMatch(AccessibilityNodeInfo n,boolean full,int d){node=n;fullscreenLike=full;depth=d;}
    }

    /** Child-first positional walk. No priority score and no semantic sorting. */
    private PositionMatch findByPosition(AccessibilityNodeInfo n,int x,int y,Rect screen,int depth,int[] count){
        if(n==null||count[0]++>1600||depth>72)return null;
        try{if(!n.isVisibleToUser())return null;}catch(Throwable ignored){}

        Rect r=new Rect();
        try{n.getBoundsInScreen(r);}catch(Throwable t){return null;}
        if(r.isEmpty()||!r.contains(x,y))return null;

        PositionMatch fullscreenChildFallback=null;
        int childCount=Math.min(n.getChildCount(),220);
        // Reverse child traversal follows common Android drawing order. This is not semantic
        // ranking: first concrete child at the coordinate wins.
        for(int i=childCount-1;i>=0;i--){
            AccessibilityNodeInfo child=null;
            try{child=n.getChild(i);}catch(Throwable ignored){}
            if(child==null)continue;
            PositionMatch hit=findByPosition(child,x,y,screen,depth+1,count);
            if(hit==null)continue;
            if(!hit.fullscreenLike)return hit;
            if(fullscreenChildFallback==null)fullscreenChildFallback=hit;
        }

        boolean currentFullscreen=isFullscreenLike(r,screen);
        if(!currentFullscreen)return new PositionMatch(n,false,depth);
        if(fullscreenChildFallback!=null)return fullscreenChildFallback;
        return new PositionMatch(n,true,depth);
    }

    /** Only near-screen-size nodes are delayed to the final fallback. */
    private boolean isFullscreenLike(Rect r,Rect screen){
        if(r==null||r.isEmpty()||screen==null||screen.isEmpty())return false;
        long area=(long)r.width()*r.height();
        long screenArea=(long)screen.width()*screen.height();
        if(screenArea<=0)return false;
        boolean nearlyFullArea=area>=screenArea*88L/100L;
        boolean nearlyFullDimensions=r.width()>=screen.width()*94L/100L
                && r.height()>=screen.height()*90L/100L;
        return nearlyFullArea||nearlyFullDimensions;
    }

    private Rect currentScreenBounds(){
        try{return new Rect(((WindowManager)getSystemService(WINDOW_SERVICE)).getCurrentWindowMetrics().getBounds());}
        catch(Throwable t){return new Rect(0,0,Integer.MAX_VALUE/4,Integer.MAX_VALUE/4);}
    }

    private boolean isExplicitImageNode(AccessibilityNodeInfo n){
        String cls="";
        String id="";
        try{if(n.getClassName()!=null)cls=n.getClassName().toString().toLowerCase(Locale.ROOT);}catch(Throwable ignored){}
        try{if(n.getViewIdResourceName()!=null)id=n.getViewIdResourceName().toLowerCase(Locale.ROOT);}catch(Throwable ignored){}
        return cls.contains("imageview")||cls.contains("imagebutton")||cls.contains("iconview")
                ||cls.endsWith(".image")||id.endsWith("/icon")||id.contains("_icon")
                ||id.contains("/image")||id.contains("_image")||id.contains("avatar")||id.contains("thumbnail");
    }

    /** Classification only; it never influences positional selection order. */
    private boolean isCompactIconLikeNode(AccessibilityNodeInfo n,Rect r,boolean actionable,boolean text){
        if(text||r==null||r.isEmpty())return false;
        boolean focusable=false;
        try{focusable=n.isFocusable();}catch(Throwable ignored){}
        int children=0;
        try{children=n.getChildCount();}catch(Throwable ignored){}
        if(!(actionable||focusable)||children>2)return false;
        float min=dp(12),max=dp(180);
        int w=r.width(),h=r.height();
        if(w<min||h<min||w>max||h>max)return false;
        float ratio=Math.max(w,h)/(float)Math.max(1,Math.min(w,h));
        return ratio<=3.5f;
    }

    private ViewNodeCandidate snapshot(AccessibilityNodeInfo n){
        Rect r=new Rect(); n.getBoundsInScreen(r);
        StringBuilder text=new StringBuilder();
        appendNodeText(n,text,0,new int[]{0});
        String cls=n.getClassName()==null?"":n.getClassName().toString();
        String id="";
        try{id=n.getViewIdResourceName()==null?"":n.getViewIdResourceName();}catch(Throwable ignored){}
        boolean clickable=false,editable=false,focusable=false;
        try{clickable=n.isClickable()||n.isLongClickable();}catch(Throwable ignored){}
        try{editable=n.isEditable();}catch(Throwable ignored){}
        try{focusable=n.isFocusable();}catch(Throwable ignored){}
        boolean image=isExplicitImageNode(n)||isCompactIconLikeNode(n,r,clickable||editable,text.length()>0);
        return new ViewNodeCandidate(r,text.toString().trim(),cls,id,clickable,editable,focusable,image);
    }

    private void appendNodeText(AccessibilityNodeInfo n,StringBuilder out,int depth,int[] count){
        if(n==null||count[0]++>240||depth>16)return;
        appendUnique(out,n.getText());
        appendUnique(out,n.getContentDescription());
        try{appendUnique(out,n.getHintText());}catch(Throwable ignored){}
        int children=Math.min(n.getChildCount(),80);
        for(int i=0;i<children;i++){
            AccessibilityNodeInfo c=null; try{c=n.getChild(i);}catch(Throwable ignored){}
            if(c!=null)appendNodeText(c,out,depth+1,count);
        }
    }

    private void appendUnique(StringBuilder out,CharSequence cs){
        if(cs==null)return;
        String s=cs.toString().trim();
        if(s.isEmpty())return;
        if(out.indexOf(s)>=0)return;
        if(out.length()>0)out.append('\n');
        out.append(s);
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

    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
}
