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

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        s=this;
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
     * FV-style hit testing. Static analysis of FooAccessibilityService.X() shows the important
     * order: visible-to-user -> bounds contains(x,y) -> recurse children first -> accept the
     * current node according to interaction/node semantics. We keep text-leaf fallback for
     * extraction, but do not use the previous "smallest area wins" heuristic.
     */
    public ViewNodeCandidate findViewAt(float x, float y) {
        try {
            List<AccessibilityWindowInfo> windows=getWindows();
            if(windows==null)return null;
            int px=Math.round(x), py=Math.round(y);
            for(int i=windows.size()-1;i>=0;i--){
                AccessibilityWindowInfo w=windows.get(i);
                if(w==null)continue;
                AccessibilityNodeInfo root=null;
                try{root=w.getRoot();}catch(Throwable ignored){}
                if(root==null)continue;
                String pkg="";
                try{if(root.getPackageName()!=null)pkg=root.getPackageName().toString();}catch(Throwable ignored){}
                if(getPackageName().equals(pkg))continue;
                NodeMatch m=findChildFirst(root,px,py,0,new int[]{0});
                if(m!=null&&m.node!=null)return snapshot(m.node);
            }
            return null;
        } catch(Throwable t){
            DiagnosticLog.i(this,"VIEW_PICK","findViewAt failed="+t);
            return null;
        }
    }

    private static final class NodeMatch {
        AccessibilityNodeInfo node;
        int priority;
        int depth;
        NodeMatch(AccessibilityNodeInfo n,int p,int d){node=n;priority=p;depth=d;}
    }

    private NodeMatch findChildFirst(AccessibilityNodeInfo n,int x,int y,int depth,int[] count){
        if(n==null||count[0]++>600||depth>40)return null;
        try{if(!n.isVisibleToUser())return null;}catch(Throwable ignored){}
        Rect r=new Rect();
        try{n.getBoundsInScreen(r);}catch(Throwable t){return null;}
        if(r.isEmpty()||!r.contains(x,y))return null;

        NodeMatch bestChild=null;
        int childCount=Math.min(n.getChildCount(),100);
        // Reverse traversal better matches top-most visual children in many Android hierarchies.
        for(int i=childCount-1;i>=0;i--){
            AccessibilityNodeInfo c=null;
            try{c=n.getChild(i);}catch(Throwable ignored){}
            if(c==null)continue;
            NodeMatch hit=findChildFirst(c,x,y,depth+1,count);
            if(hit!=null){
                if(bestChild==null||hit.priority>bestChild.priority||(hit.priority==bestChild.priority&&hit.depth>bestChild.depth))bestChild=hit;
                if(hit.priority>=3)return hit;
            }
        }
        if(bestChild!=null&&bestChild.priority>=2)return bestChild;

        int p=nodePriority(n);
        if(p>0)return new NodeMatch(n,p,depth);
        return bestChild;
    }

    /** Priority mirrors FV's preference for actionable nodes, with text as extraction fallback. */
    private int nodePriority(AccessibilityNodeInfo n){
        try{
            if(n.isClickable()||n.isLongClickable()||n.isEditable())return 3;
            if(hasOwnText(n))return 2;
            if(n.getChildCount()==0)return 1;
        }catch(Throwable ignored){}
        return 0;
    }

    private boolean hasOwnText(AccessibilityNodeInfo n){
        try{return nonBlank(n.getText())||nonBlank(n.getContentDescription())||nonBlank(n.getHintText())||nonBlank(n.getStateDescription());}
        catch(Throwable t){return false;}
    }
    private boolean nonBlank(CharSequence s){return s!=null&&!s.toString().trim().isEmpty();}

    private ViewNodeCandidate snapshot(AccessibilityNodeInfo n){
        Rect r=new Rect(); n.getBoundsInScreen(r);
        StringBuilder text=new StringBuilder();
        appendNodeText(n,text,0,new int[]{0});
        String cls=n.getClassName()==null?"":n.getClassName().toString();
        String id="";
        try{id=n.getViewIdResourceName()==null?"":n.getViewIdResourceName();}catch(Throwable ignored){}
        return new ViewNodeCandidate(r,text.toString().trim(),cls,id,n.isClickable(),n.isEditable());
    }

    /** FV S0() snapshots bounds/text/class/clickability/collection metadata; this is our text subset. */
    private void appendNodeText(AccessibilityNodeInfo n,StringBuilder out,int depth,int[] count){
        if(n==null||count[0]++>180||depth>12)return;
        appendUnique(out,n.getText());
        appendUnique(out,n.getContentDescription());
        try{appendUnique(out,n.getHintText());}catch(Throwable ignored){}
        int children=Math.min(n.getChildCount(),60);
        for(int i=0;i<children;i++){
            AccessibilityNodeInfo c=null; try{c=n.getChild(i);}catch(Throwable ignored){}
            if(c!=null)appendNodeText(c,out,depth+1,count);
        }
    }
    private void appendUnique(StringBuilder out,CharSequence cs){
        if(cs==null)return; String s=cs.toString().trim(); if(s.isEmpty())return;
        if(out.indexOf(s)>=0)return; if(out.length()>0)out.append('\n'); out.append(s);
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
