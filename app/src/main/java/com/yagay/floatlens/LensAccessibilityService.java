package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
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
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class LensAccessibilityService extends AccessibilityService {
    private static volatile LensAccessibilityService s;
    private volatile EnvironmentState env = new EnvironmentState("", false, 0, true, false, false);

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        s=this;
        // OOS and some Android builds do not reliably apply all XML flags until the service is
        // rebound. Force the FV-required window/node visibility flags at runtime as well.
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
     * FV-style coordinate hit testing: visible -> bounds contains -> inspect children -> choose the
     * most meaningful visual/accessibility node. Image/icon nodes are first-class candidates even
     * when they expose no text. This is important for toolbar icons, image buttons and Compose
     * icon nodes whose clickable parent contains no textual accessibility data.
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
                if(m!=null&&m.node!=null){
                    ViewNodeCandidate out=snapshot(m.node);
                    DiagnosticLog.i(this,"VIEW_PICK","hit kind="+out.kind()+" p="+m.priority+" bounds="+out.bounds()+" class="+out.className()+" id="+out.viewId()+" textLen="+out.text().length());
                    return out;
                }
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
        if(n==null||count[0]++>800||depth>48)return null;
        try{if(!n.isVisibleToUser())return null;}catch(Throwable ignored){}
        Rect r=new Rect();
        try{n.getBoundsInScreen(r);}catch(Throwable t){return null;}
        if(r.isEmpty()||!r.contains(x,y))return null;

        NodeMatch bestChild=null;
        int childCount=Math.min(n.getChildCount(),120);
        // Reverse traversal approximates the top-most visual child. Do not immediately return an
        // ordinary clickable child: a deeper ImageView/ImageButton must be allowed to beat its
        // generic clickable parent/container.
        for(int i=childCount-1;i>=0;i--){
            AccessibilityNodeInfo c=null;
            try{c=n.getChild(i);}catch(Throwable ignored){}
            if(c==null)continue;
            NodeMatch hit=findChildFirst(c,x,y,depth+1,count);
            if(hit!=null){
                if(bestChild==null||hit.priority>bestChild.priority||(hit.priority==bestChild.priority&&hit.depth>bestChild.depth))bestChild=hit;
                if(hit.priority>=6)return hit; // explicit actionable image/icon: strongest match
            }
        }

        int currentPriority=nodePriority(n,r);
        if(bestChild!=null){
            if(bestChild.priority>currentPriority)return bestChild;
            if(bestChild.priority==currentPriority&&bestChild.depth>depth)return bestChild;
        }
        if(currentPriority>0)return new NodeMatch(n,currentPriority,depth);
        return bestChild;
    }

    /**
     * Priority model: explicit image/icon nodes > compact visual icon nodes > generic actionable
     * container > text > neutral leaf. This prevents a clickable toolbar parent from swallowing its
     * actual ImageButton child, while rejecting giant decorative/background ImageViews.
     */
    private int nodePriority(AccessibilityNodeInfo n,Rect r){
        try{
            boolean actionable=n.isClickable()||n.isLongClickable()||n.isEditable();
            boolean text=hasOwnText(n);
            boolean explicitImage=isExplicitImageNode(n);

            if(explicitImage&&!isLargeDecorativeImage(r,actionable,text))return actionable?6:5;
            if(isCompactIconLikeNode(n,r,actionable,text))return 4;
            if(actionable)return 3;
            if(text)return 2;
            if(n.getChildCount()==0&&isReasonableLeaf(r))return 1;
        }catch(Throwable ignored){}
        return 0;
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

    /** Generic Compose/custom-view icon: compact, leaf-ish, no text, and interactive/focusable. */
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

    private boolean isReasonableLeaf(Rect r){
        if(r==null||r.isEmpty())return false;
        try{
            Rect screen=((WindowManager)getSystemService(WINDOW_SERVICE)).getCurrentWindowMetrics().getBounds();
            return r.width()<screen.width()*0.92f||r.height()<screen.height()*0.92f;
        }catch(Throwable t){return true;}
    }

    private boolean isLargeDecorativeImage(Rect r,boolean actionable,boolean text){
        if(actionable||text||r==null||r.isEmpty())return false;
        try{
            Rect screen=((WindowManager)getSystemService(WINDOW_SERVICE)).getCurrentWindowMetrics().getBounds();
            return r.width()>screen.width()*0.78f&&r.height()>screen.height()*0.55f;
        }catch(Throwable t){return false;}
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
        boolean clickable=false,editable=false,focusable=false;
        try{clickable=n.isClickable()||n.isLongClickable();}catch(Throwable ignored){}
        try{editable=n.isEditable();}catch(Throwable ignored){}
        try{focusable=n.isFocusable();}catch(Throwable ignored){}
        boolean image=isExplicitImageNode(n)||isCompactIconLikeNode(n,r,clickable||editable,text.length()>0);
        return new ViewNodeCandidate(r,text.toString().trim(),cls,id,clickable,editable,focusable,image);
    }

    /** FV S0() snapshots bounds/text/class/clickability/collection metadata; this is our selection subset. */
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

    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
}