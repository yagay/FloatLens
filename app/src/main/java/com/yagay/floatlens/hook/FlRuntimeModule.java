package com.yagay.floatlens.hook;

import android.content.*;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class FlRuntimeModule extends XposedModule {
    private static final String TARGET="com.fooview.android.fooview";
    private static final String TAG="FloatLens-FLInspector";
    private volatile boolean installed;
    private final Set<String> hooked=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicInteger> probeCounts=new ConcurrentHashMap<>();
    private volatile long lastMoveLog;

    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p){
        if(!TARGET.equals(p.getPackageName()))return;
        HookLogTransport.log("SELFTEST","target_loaded=true package="+p.getPackageName()+" module=2.3.0-full");
        install(p.getDefaultClassLoader());
    }

    private synchronized void install(ClassLoader cl){if(installed)return;installed=true;int n=0;
        n+=hookApplicationAttach(cl);
        n+=hookNamed(cl,"com.fooview.android.fooview.fvprocess.FooViewService$c3",new String[]{"onTouch","b"},"TOUCH",true);
        n+=hookNamed(cl,"com.fooview.android.fooview.fvprocess.FooViewService",new String[]{"Y1","y3","R4","q4","h0","i3","k3","l3","onCreate","onStartCommand","onDestroy","onConfigurationChanged"},"SERVICE",true);
        n+=hookL3Downstream(cl);
        n+=hookNamed(cl,"com.fooview.android.fooview.fvprocess.FloatIconView",new String[]{"c0","T"},"POSITION",true);
        n+=hookNamed(cl,"com.fooview.android.gesture.GesturePanel",new String[]{"dispatchTouchEvent","onTouchEvent","onTouch","clear","reset"},"GESTURE",false);
        n+=hookNamed(cl,"android.gesture.GestureStore",new String[]{"recognize","addGesture","removeGesture"},"GESTURE_STORE",false);
        n+=hookPreferences(cl);
        n+=hookWindowManager(cl);
        n+=hookHandlerTiming();
        n+=hookKnownRunnables(cl);
        n+=installFullCapture(cl);
        HookLogTransport.log("INSTALL","hooks="+n+" module=2.3.0-full mode=FULL_CAPTURE");
        emitSelfTest(cl);
        log(Log.INFO,TAG,"installed hooks="+n);
    }

    private int installFullCapture(ClassLoader cl){
        String[] classes={
            "com.fooview.android.fooview.fvprocess.FooViewService",
            "com.fooview.android.fooview.fvprocess.FooViewService$c3",
            "com.fooview.android.fooview.fvprocess.FloatIconView",
            "com.fooview.android.gesture.GesturePanel",
            "com.fooview.android.fooview.settings.FooGestureSetting",
            "com.fooview.android.fooview.fvprocess.CircleAppContainer",
            "com.fooview.android.fooview.fvprocess.CircleGuideContainer",
            "com.fooview.android.fooview.fvprocess.CircleGuideScreenShot",
            "com.fooview.android.fooview.fvprocess.CircleService",
            "com.fooview.android.fooview.fvprocess.CircleServiceReceiver",
            "com.fooview.android.gesture.FVCandidateAdapter",
            "com.fooview.android.gesture.FVCandidateHWAdapter",
            "com.fooview.android.gesture.ocrresult.OCRTextResultAdapter",
            "com.fooview.android.gesture.ocrresult.OCRImageCandidateViewWrapper",
            "com.fooview.android.gesture.circleReco.FVMediaProjectionService",
            "com.fooview.android.fooview.screencapture.y"
        };
        int n=0;
        for(String cn:classes)n+=hookAllExisting(cl,cn,"FULL_CAPTURE",true);
        Class<?> svc=find(cl,"com.fooview.android.fooview.fvprocess.FooViewService");
        if(svc!=null){
            for(Class<?> inner:svc.getDeclaredClasses()){
                String name=inner.getName();
                if(name.contains("$"))n+=hookAllExisting(inner,"FULL_INNER",true);
            }
        }
        HookLogTransport.log("FULL_CAPTURE","installed="+n+" classes="+classes.length+" + FooViewService inner classes");
        return n;
    }

    private void emitSelfTest(ClassLoader cl){
        String[] classes={"com.fooview.android.fooview.fvprocess.FooViewService","com.fooview.android.fooview.fvprocess.FooViewService$c3","com.fooview.android.fooview.fvprocess.FloatIconView","com.fooview.android.gesture.GesturePanel","com.fooview.android.fooview.fvprocess.CircleAppContainer","com.fooview.android.gesture.ocrresult.OCRTextResultAdapter","com.fooview.android.gesture.circleReco.FVMediaProjectionService"};
        for(String cn:classes){Class<?> c=find(cl,cn);HookLogTransport.log("SELFTEST","class="+cn+" found="+(c!=null));}
        Class<?> s=find(cl,"com.fooview.android.fooview.fvprocess.FooViewService");
        if(s!=null){for(String m:new String[]{"Y1","y3","R4","q4","h0","i3","k3","l3"})HookLogTransport.log("SELFTEST","method=FooViewService."+m+" found="+hasMethod(s,m));}
        Class<?> c3=find(cl,"com.fooview.android.fooview.fvprocess.FooViewService$c3");
        if(c3!=null){for(String m:new String[]{"onTouch","b"})HookLogTransport.log("SELFTEST","method=FooViewService$c3."+m+" found="+hasMethod(c3,m));}
    }
    private static boolean hasMethod(Class<?> c,String n){for(Method m:c.getDeclaredMethods())if(m.getName().equals(n))return true;return false;}

    private int hookApplicationAttach(ClassLoader cl){
        try{Method m=android.app.Application.class.getDeclaredMethod("attach",Context.class);m.setAccessible(true);String key=m.toGenericString();if(!hooked.add(key))return 0;hook(m).intercept(chain->{Object r=chain.proceed();try{Object a=chain.getThisObject();Object base=chain.getArg(0);Context c=a instanceof Context?(Context)a:(base instanceof Context?(Context)base:null);if(c!=null){HookLogTransport.init(c);registerCommand(c);HookLogTransport.log("SESSION","FL Runtime Inspector 2.3.0 FULL_CAPTURE api=102 sdk="+Build.VERSION.SDK_INT+" target="+TARGET);}}catch(Throwable t){HookLogTransport.log("INIT_ERR",String.valueOf(t));}return r;});return 1;}catch(Throwable t){HookLogTransport.log("HOOK_ERR","Application.attach "+t);return 0;}
    }

    private int hookNamed(ClassLoader cl,String cn,String[] names,String cat,boolean snapshot){Class<?> c=find(cl,cn);if(c==null){HookLogTransport.log("MISS","class "+cn);return 0;}Set<String> ns=new HashSet<>(Arrays.asList(names));int n=0;for(Method m:c.getDeclaredMethods())if(ns.contains(m.getName()))n+=hookMethod(m,cat,snapshot);return n;}
    private int hookAllExisting(ClassLoader cl,String cn,String cat,boolean snapshot){Class<?> c=find(cl,cn);if(c==null){HookLogTransport.log("FULL_MISS","class="+cn);return 0;}return hookAllExisting(c,cat,snapshot);}
    private int hookAllExisting(Class<?> c,String cat,boolean snapshot){int n=0;for(Method m:c.getDeclaredMethods()){if(m.isSynthetic()||m.isBridge()||m.getName().equals("toString")||m.getName().equals("hashCode"))continue;n+=hookMethod(m,cat,snapshot);}return n;}

    private int hookMethod(Method m,String cat,boolean snapshot){String key=m.toGenericString();if(!hooked.add(key))return 0;try{m.setAccessible(true);hook(m).intercept(chain->{
        boolean enabled=enabled(cat); if(!enabled)return chain.proceed();
        List<?> args=chain.getArgs();
        String dc=m.getDeclaringClass().getName(), mn=m.getName();
        if(dc.endsWith("FooViewService$c3") && mn.equals("onTouch"))FlSessionTracker.onTouch(args);
        if(dc.endsWith("FooViewService$c3") && mn.equals("b")){Integer code=firstInt(args);if(code!=null)FlSessionTracker.onCode(code,"b");}
        if(dc.endsWith("FooViewService") && mn.equals("Y1")){Integer code=firstInt(args);if(code!=null)FlSessionTracker.onCode(code,"Y1");}
        if(dc.endsWith("FooViewService") && (mn.equals("i3")||mn.equals("k3")||mn.equals("l3")))FlSessionTracker.onActionLayer(mn,args);
        if(cat.equals("TOUCH")&&args.stream().anyMatch(x->x instanceof MotionEvent e&&e.getActionMasked()==MotionEvent.ACTION_MOVE)){long now=android.os.SystemClock.uptimeMillis();if(now-lastMoveLog<16)return chain.proceed();lastMoveLog=now;}
        boolean bounded=cat.equals("FULL_CAPTURE")||cat.equals("FULL_INNER");
        if(bounded&&!shouldProbeLog(key))return chain.proceed();
        Object th=chain.getThisObject();Map<String,String> before=snapshot&&FlInspectorConfig.objectDiff?ObjectSnapshot.take(th):Map.of();long st=android.os.SystemClock.elapsedRealtimeNanos();
        HookLogTransport.log(cat,"ENTER "+HookFmt.member(m)+" args="+HookFmt.args(args)+" stack="+HookFmt.stack(8));
        Object result;try{result=chain.proceed();}catch(Throwable t){HookLogTransport.log(cat,"THROW "+HookFmt.member(m)+" "+t);throw t;}
        long us=(android.os.SystemClock.elapsedRealtimeNanos()-st)/1000;String diff=snapshot&&FlInspectorConfig.objectDiff?" diff="+ObjectSnapshot.diff(before,ObjectSnapshot.take(th)):"";
        HookLogTransport.log(cat,"EXIT "+HookFmt.member(m)+" result="+HookFmt.value(result)+" us="+us+diff);return result;
    });HookLogTransport.log("SELFTEST","hooked="+HookFmt.member(m));return 1;}catch(Throwable t){HookLogTransport.log("HOOK_ERR",key+" :: "+t);return 0;}}

    private boolean shouldProbeLog(String key){int n=probeCounts.computeIfAbsent(key,k->new AtomicInteger()).incrementAndGet();return n<=12||n==20||n==50||n%100==0;}

    private int hookL3Downstream(ClassLoader cl){Class<?> c=find(cl,"com.fooview.android.fooview.fvprocess.FooViewService");if(c==null)return 0;int n=0;for(Method m:c.getDeclaredMethods()){if(m.isSynthetic())continue;String mn=m.getName();if(mn.equals("i3")||mn.equals("k3")||mn.equals("l3")||mn.equals("Y1"))continue;String key="l3downstream:"+m.toGenericString();if(!hooked.add(key))continue;try{m.setAccessible(true);hook(m).intercept(chain->{if(calledFrom("l3"))FlSessionTracker.onDownstream(m.getDeclaringClass().getSimpleName(),m.getName(),chain.getArgs());return chain.proceed();});n++;}catch(Throwable t){HookLogTransport.log("HOOK_ERR","l3downstream "+m.toGenericString()+" :: "+t);}}return n;}

    private int hookHandlerTiming(){int n=0;try{for(Method m:Handler.class.getDeclaredMethods()){String name=m.getName();if(!(name.equals("postDelayed")||name.equals("postAtTime")||name.equals("removeCallbacks")))continue;String key="handler:"+m.toGenericString();if(!hooked.add(key))continue;m.setAccessible(true);hook(m).intercept(chain->{Object first=chain.getArgs().isEmpty()?null:chain.getArg(0);if(first instanceof Runnable r&&r.getClass().getName().startsWith("com.fooview"))HookLogTransport.log("TIMING","Handler."+m.getName()+" runnable="+r.getClass().getName()+" args="+HookFmt.args(chain.getArgs())+" stack="+HookFmt.stack(7));return chain.proceed();});n++;}}catch(Throwable t){HookLogTransport.log("HOOK_ERR","handler timing "+t);}return n;}

    private static boolean calledFrom(String method){for(StackTraceElement e:Thread.currentThread().getStackTrace())if(e.getClassName().equals("com.fooview.android.fooview.fvprocess.FooViewService")&&e.getMethodName().equals(method))return true;return false;}
    private static Integer firstInt(List<?> args){for(Object o:args)if(o instanceof Integer i)return i;return null;}
    private int hookPreferences(ClassLoader cl){if(!FlInspectorConfig.preferences)return 0;int n=0;try{Class<?> c=Class.forName("android.app.SharedPreferencesImpl");for(Method m:c.getDeclaredMethods()){String x=m.getName();if(x.equals("getInt")||x.equals("getLong")||x.equals("getFloat")||x.equals("getBoolean")||x.equals("getString")||x.equals("contains"))n+=hookMethod(m,"PREF",false);}}catch(Throwable t){HookLogTransport.log("HOOK_ERR","prefs "+t);}try{Class<?> c=Class.forName("android.app.SharedPreferencesImpl$EditorImpl");for(Method m:c.getDeclaredMethods())if(m.getName().startsWith("put")||m.getName().equals("remove")||m.getName().equals("clear")||m.getName().equals("apply")||m.getName().equals("commit"))n+=hookMethod(m,"PREF_WRITE",false);}catch(Throwable t){HookLogTransport.log("HOOK_ERR","editor "+t);}return n;}
    private int hookWindowManager(ClassLoader cl){int n=0;try{Class<?> c=Class.forName("android.view.WindowManagerGlobal");for(Method m:c.getDeclaredMethods())if(m.getName().equals("addView")||m.getName().equals("updateViewLayout")||m.getName().equals("removeView")||m.getName().equals("removeViewImmediate"))n+=hookMethod(m,"WINDOW",false);}catch(Throwable t){HookLogTransport.log("HOOK_ERR","window "+t);}return n;}
    private int hookKnownRunnables(ClassLoader cl){int n=0;String[] cs={"com.fooview.android.fooview.fvprocess.FooViewService$q","com.fooview.android.fooview.fvprocess.FooViewService$c3$a","com.fooview.android.fooview.fvprocess.FooViewService$c3$b","com.fooview.android.fooview.fvprocess.FooViewService$c3$c","com.fooview.android.fooview.fvprocess.FooViewService$c3$d","com.fooview.android.fooview.fvprocess.FooViewService$c3$f"};for(String cn:cs)n+=hookNamed(cl,cn,new String[]{"run"},"RUNNABLE",true);return n;}
    private boolean enabled(String cat){return switch(cat){case "TOUCH"->FlInspectorConfig.touch;case "GESTURE"->FlInspectorConfig.gesture;case "POSITION"->FlInspectorConfig.position;case "PREF","PREF_WRITE"->FlInspectorConfig.preferences;case "WINDOW"->FlInspectorConfig.window;case "SCREENSHOT"->FlInspectorConfig.screenshot;case "OCR","CANDIDATE","CIRCLE_CORE","CIRCLE_GUIDE","FULL_CAPTURE","FULL_INNER"->true;case "GESTURE_STORE"->FlInspectorConfig.gesture;default->true;};}
    private void registerCommand(Context c){try{IntentFilter f=new IntentFilter(HookLogTransport.ACTION_CMD);BroadcastReceiver r=new BroadcastReceiver(){@Override public void onReceive(Context x,Intent i){String cmd=i.getStringExtra("cmd");if("all_on".equals(cmd))FlInspectorConfig.setAll(true);else if("all_off".equals(cmd))FlInspectorConfig.setAll(false);else if("selftest".equals(cmd))emitSelfTest(c.getClassLoader());HookLogTransport.log("CMD","cmd="+cmd);}};if(Build.VERSION.SDK_INT>=33)c.registerReceiver(r,f,Context.RECEIVER_EXPORTED);else c.registerReceiver(r,f);}catch(Throwable t){HookLogTransport.log("CMD_ERR",String.valueOf(t));}}
    private static Class<?> find(ClassLoader cl,String n){try{return Class.forName(n,false,cl);}catch(Throwable t){return null;}}
}
