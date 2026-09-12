package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class LensAccessibilityService extends AccessibilityService {
    private static volatile LensAccessibilityService s;
    private static final long ENV_INSPECT_MIN_MS = 180L;
    private volatile EnvironmentState env = new EnvironmentState("", false, 0, true, false, false);
    private final Set<String> homePackages = new HashSet<>();
    private long lastEnvironmentInspectAt;

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
        try { refreshHomePackages(); }
        catch (Throwable t) { DiagnosticLog.i(this,"ACCESSIBILITY","home package query failed="+t); }
        try {
            env=inspect(env.topPackage());
            publishEnvironment();
        } catch (Throwable t) {
            DiagnosticLog.i(this,"ACCESSIBILITY","publish on connect failed="+t);
        }
        FloatService f=FloatService.get();
        if(f!=null) f.onAccessibilityOverlayHostChanged(true);
    }

    @Override public void onDestroy(){
        FloatService f=FloatService.get();
        if(f!=null) f.onAccessibilityOverlayHostChanged(false);
        FvSystemPanelController.onAccessibilityDisconnected(this);
        if(s==this)s=null;
        super.onDestroy();
    }

    /**
     * FV's window helper switches floating windows to type 2032 when accessibility is available.
     * 2032 is TYPE_ACCESSIBILITY_OVERLAY. Hosting the icon from the AccessibilityService's own
     * WindowManager gives it the accessibility overlay token/layer so it remains above SystemUI's
     * notification shade and quick settings, just like FV.
     */
    public boolean addAccessibilityOverlay(View view, WindowManager.LayoutParams lp) {
        if(view==null||lp==null)return false;
        try {
            lp.type=WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            ((WindowManager)getSystemService(WINDOW_SERVICE)).addView(view,lp);
            DiagnosticLog.i(this,"FV_WINDOW","add accessibility overlay type="+lp.type);
            return true;
        } catch(Throwable t) {
            DiagnosticLog.i(this,"FV_WINDOW","add accessibility overlay failed="+t);
            return false;
        }
    }

    public boolean updateAccessibilityOverlay(View view, WindowManager.LayoutParams lp) {
        if(view==null||lp==null)return false;
        try {
            lp.type=WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            ((WindowManager)getSystemService(WINDOW_SERVICE)).updateViewLayout(view,lp);
            return true;
        } catch(Throwable t) {
            DiagnosticLog.i(this,"FV_WINDOW","update accessibility overlay failed="+t);
            return false;
        }
    }

    public void removeAccessibilityOverlay(View view) {
        if(view==null)return;
        try { ((WindowManager)getSystemService(WINDOW_SERVICE)).removeView(view); }
        catch(Throwable ignored) {}
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            // A focusable TYPE_APPLICATION_OVERLAY is not guaranteed to lose focus when the user
            // presses Home or Recents. Detect the actual launcher/overview window transition instead.
            // Do this before environment throttling so a fast navigation press can never be skipped.
            if (isSystemNavigationEvent(e)) {
                String pkg = eventPackage(e);
                String cls = eventClass(e);
                DiagnosticLog.i(this,"CIRCLE_SELECT","system navigation event pkg="+pkg+" cls="+cls);
                CircleSelectOverlay.dismissActive("system_navigation");
            }

            String oldTop = env.topPackage();
            String top = oldTop;
            if (e != null && e.getPackageName() != null) {
                String pkg=e.getPackageName().toString();
                if (!pkg.equals(getPackageName()) && !pkg.equals("com.android.systemui")) top=pkg;
            }
            boolean topChanged = !top.equals(oldTop);
            long now = SystemClock.uptimeMillis();
            if (!topChanged && now - lastEnvironmentInspectAt < ENV_INSPECT_MIN_MS) return;
            lastEnvironmentInspectAt = now;
            EnvironmentState next = inspect(top);
            if (sameEnvironment(env, next)) return;
            env = next;
            publishEnvironment();
        } catch (Throwable t) {
            DiagnosticLog.i(this,"ACCESSIBILITY","event failed="+t);
        }
    }

    private void refreshHomePackages() {
        homePackages.clear();
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        PackageManager pm = getPackageManager();
        try {
            ResolveInfo resolved = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved != null && resolved.activityInfo != null && resolved.activityInfo.packageName != null) {
                String pkg = resolved.activityInfo.packageName;
                if (!pkg.equals("android")) homePackages.add(pkg);
            }
        } catch (Throwable ignored) {}
        try {
            List<ResolveInfo> homes = pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (homes != null) for (ResolveInfo info : homes) {
                if (info == null || info.activityInfo == null || info.activityInfo.packageName == null) continue;
                String pkg = info.activityInfo.packageName;
                if (!pkg.equals("android")) homePackages.add(pkg);
            }
        } catch (Throwable ignored) {}
        DiagnosticLog.i(this,"ACCESSIBILITY","home packages="+homePackages);
    }

    private boolean isSystemNavigationEvent(AccessibilityEvent e) {
        if (e == null) return false;
        int type = e.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return false;

        String pkg = eventPackage(e);
        String cls = eventClass(e);
        if (isHomePackage(pkg)) return true;
        if (isRecentsWindow(pkg, cls)) return true;

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) for (AccessibilityWindowInfo w : windows) {
                if (w == null || (!w.isActive() && !w.isFocused())) continue;
                AccessibilityNodeInfo root = null;
                try { root = w.getRoot(); } catch (Throwable ignored) {}
                String activePkg = nodePackage(root);
                if (isHomePackage(activePkg)) return true;
                String activeCls = "";
                try {
                    if (root != null && root.getClassName() != null) activeCls = root.getClassName().toString();
                } catch (Throwable ignored) {}
                if (isRecentsWindow(activePkg, activeCls)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isHomePackage(String pkg) {
        if (pkg == null || pkg.isBlank()) return false;
        if (homePackages.isEmpty()) {
            try { refreshHomePackages(); } catch (Throwable ignored) {}
        }
        return homePackages.contains(pkg);
    }

    private boolean isRecentsWindow(String pkg, String cls) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        String c = cls == null ? "" : cls.toLowerCase(Locale.ROOT);
        boolean recentsName = c.contains("recents") || c.contains("overview")
                || c.contains("recenttask") || c.contains("taskoverview")
                || c.contains("task_switch") || c.contains("taskswitch");
        if (!recentsName) return false;
        return "com.android.systemui".equals(p) || isHomePackage(pkg)
                || p.contains("launcher") || p.contains("quickstep") || p.contains("systemui");
    }

    private String eventPackage(AccessibilityEvent e) {
        try { return e != null && e.getPackageName() != null ? e.getPackageName().toString() : ""; }
        catch (Throwable t) { return ""; }
    }

    private String eventClass(AccessibilityEvent e) {
        try { return e != null && e.getClassName() != null ? e.getClassName().toString() : ""; }
        catch (Throwable t) { return ""; }
    }

    private boolean sameEnvironment(EnvironmentState a, EnvironmentState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.topPackage().equals(b.topPackage())
                && a.imeVisible() == b.imeVisible()
                && a.imeTopPx() == b.imeTopPx()
                && a.statusBarVisible() == b.statusBarVisible()
                && a.notificationExpanded() == b.notificationExpanded()
                && a.fullscreen() == b.fullscreen();
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
     * FV r0(x,y)-style collection: return the whole Accessibility candidate chain at the point,
     * not one pre-ranked node. Window order is Android's documented top-most -> bottom-most order.
     * Candidate attributes are descriptive only; final selection is performed geometrically by
     * ScreenSelectionModel.
     */
    public List<ScreenCandidate> collectCandidatesAt(float x, float y) {
        ArrayList<ScreenCandidate> out=new ArrayList<>();
        try {
            final int px=Math.round(x), py=Math.round(y);
            final Rect screen=currentScreenBounds();
            List<AccessibilityWindowInfo> windows=getWindows();
            if(windows!=null){
                for(int i=0;i<windows.size();i++){
                    AccessibilityWindowInfo w=windows.get(i);
                    if(w==null||!windowContainsPoint(w,px,py))continue;
                    AccessibilityNodeInfo root=null;
                    try{root=w.getRoot();}catch(Throwable ignored){}
                    if(root==null)continue;
                    String pkg=nodePackage(root);
                    if(getPackageName().equals(pkg))continue;
                    int before=out.size();
                    collectByPosition(root,px,py,screen,0,new int[]{0},out);
                    if(out.size()>before){
                        DiagnosticLog.i(this,"VIEW_PICK","window="+i+" layer="+safeLayer(w)+" pkg="+pkg+" candidates="+(out.size()-before));
                    }
                }
            }

            try{
                AccessibilityNodeInfo active=getRootInActiveWindow();
                if(active!=null&&!getPackageName().equals(nodePackage(active))){
                    int before=out.size();
                    collectByPosition(active,px,py,screen,0,new int[]{0},out);
                    if(out.size()>before)DiagnosticLog.i(this,"VIEW_PICK","active-root pkg="+nodePackage(active)+" candidates="+(out.size()-before));
                }
            }catch(Throwable t){
                DiagnosticLog.i(this,"VIEW_PICK","active-root collection failed="+t);
            }
        } catch(Throwable t){
            DiagnosticLog.i(this,"VIEW_PICK","collectCandidatesAt failed="+t);
        }
        return out;
    }

    public ViewNodeCandidate findViewAt(float x, float y) {
        ScreenSelectionModel model=new ScreenSelectionModel();
        model.setAccessibility(collectCandidatesAt(x,y));
        ScreenCandidate selected=model.selectAt(x,y);
        return selected==null?null:selected.toViewNodeCandidate();
    }

    private void collectByPosition(AccessibilityNodeInfo n,int x,int y,Rect screen,int depth,int[] count,List<ScreenCandidate> out){
        if(n==null||count[0]++>2200||depth>80)return;
        try{if(!n.isVisibleToUser())return;}catch(Throwable ignored){}
        Rect r=new Rect();
        try{n.getBoundsInScreen(r);}catch(Throwable t){return;}
        if(r.isEmpty()||!r.contains(x,y))return;

        int children=Math.min(n.getChildCount(),260);
        for(int i=children-1;i>=0;i--){
            AccessibilityNodeInfo child=null;
            try{child=n.getChild(i);}catch(Throwable ignored){}
            if(child!=null)collectByPosition(child,x,y,screen,depth+1,count,out);
        }
        out.add(snapshotScreenCandidate(n,depth,screen));
    }

    private ScreenCandidate snapshotScreenCandidate(AccessibilityNodeInfo n,int depth,Rect screen){
        Rect r=new Rect();
        try{n.getBoundsInScreen(r);}catch(Throwable ignored){}
        StringBuilder text=new StringBuilder();
        appendNodeText(n,text,0,new int[]{0});
        String cls="",id="",pkg=nodePackage(n);
        try{if(n.getClassName()!=null)cls=n.getClassName().toString();}catch(Throwable ignored){}
        try{if(n.getViewIdResourceName()!=null)id=n.getViewIdResourceName();}catch(Throwable ignored){}
        boolean clickable=false,editable=false,focusable=false;
        try{clickable=n.isClickable()||n.isLongClickable();}catch(Throwable ignored){}
        try{editable=n.isEditable();}catch(Throwable ignored){}
        try{focusable=n.isFocusable();}catch(Throwable ignored){}
        boolean iconLike=isExplicitImageNode(n)||isCompactIconLikeNode(n,r,clickable||editable,text.length()>0);
        boolean full=isFullscreenLike(r,screen);
        ScreenCandidate.Type type=full?ScreenCandidate.Type.ROOT:
                (text.length()>0?ScreenCandidate.Type.TEXT:(iconLike?ScreenCandidate.Type.NON_TEXT:ScreenCandidate.Type.VIEW));
        return new ScreenCandidate(r,type,ScreenCandidate.Source.ACCESSIBILITY,text.toString().trim(),cls,id,pkg,
                depth,full,clickable,editable,focusable,iconLike);
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

    public Rect screenBounds(){return currentScreenBounds();}

    private Rect currentScreenBounds(){
        try{return new Rect(((WindowManager)getSystemService(WINDOW_SERVICE)).getCurrentWindowMetrics().getBounds());}
        catch(Throwable t){return new Rect(0,0,Integer.MAX_VALUE/4,Integer.MAX_VALUE/4);}
    }

    private boolean isExplicitImageNode(AccessibilityNodeInfo n){
        String cls="",id="";
        try{if(n.getClassName()!=null)cls=n.getClassName().toString().toLowerCase(Locale.ROOT);}catch(Throwable ignored){}
        try{if(n.getViewIdResourceName()!=null)id=n.getViewIdResourceName().toLowerCase(Locale.ROOT);}catch(Throwable ignored){}
        return cls.contains("imageview")||cls.contains("imagebutton")||cls.contains("iconview")
                ||cls.endsWith(".image")||id.endsWith("/icon")||id.contains("_icon")
                ||id.contains("/image")||id.contains("_image")||id.contains("avatar")||id.contains("thumbnail");
    }

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

    private void appendNodeText(AccessibilityNodeInfo n,StringBuilder out,int depth,int[] count){
        if(n==null||count[0]++>260||depth>18)return;
        appendUnique(out,n.getText());
        appendUnique(out,n.getContentDescription());
        try{appendUnique(out,n.getHintText());}catch(Throwable ignored){}
        int children=Math.min(n.getChildCount(),90);
        for(int i=0;i<children;i++){
            AccessibilityNodeInfo c=null; try{c=n.getChild(i);}catch(Throwable ignored){}
            if(c!=null)appendNodeText(c,out,depth+1,count);
        }
    }

    private void appendUnique(StringBuilder out,CharSequence cs){
        if(cs==null)return;
        String value=cs.toString().trim();
        if(value.isEmpty()||out.indexOf(value)>=0)return;
        if(out.length()>0)out.append('\n');
        out.append(value);
    }

    private void publishEnvironment() {
        FvSystemPanelController.onAccessibilityEnvironment(this, env);
        FloatService f=FloatService.get();
        if(f!=null) f.onAccessibilityEnvironment(env);
    }
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
