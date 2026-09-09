package com.yagay.floatlens.hook;

import android.content.*;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class FvRuntimeModule extends XposedModule {
    private static final String TARGET="com.fooview.android.fooview";
    private static final String TAG="FloatLens-FVInspector";
    private volatile boolean installed;
    private final Set<String> hooked=ConcurrentHashMap.newKeySet();
    private volatile long lastMoveLog;

    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p){
        if(!TARGET.equals(p.getPackageName()))return;
        HookLogTransport.log("SELFTEST","target_loaded=true package="+p.getPackageName()+" module=2.2.5");
        install(p.getDefaultClassLoader());
    }

    private synchronized void install(ClassLoader cl){if(installed)return;installed=true;int n=0;
        n+=hookApplicationAttach(cl);
        n+=hookNamed(cl,"com.fooview.android.fooview.fvprocess.FooViewService$c3",new String[]{"onTouch","b"},"TOUCH",true);
        n+=hookNamed(cl,"com.fooview.android.fooview.fvprocess.FooViewService",new String[]{"Y1","y3","R4","q4","h0","i3","k3","l3","onCreate","onStartCommand","onDestroy","onConfigurationChanged"},"SERVICE",true);
        n+=hookNamed(cl,"com.fooview.android.fooview.fvprocess.FloatIconView",new String[]{"c0","T"},"POSITION",true);
        n+=hookNamed(cl,"com.fooview.android.gesture.GesturePanel",new String[]{"dispatchTouchEvent","onTouchEvent","onTouch","clear","reset"},"GESTURE",false);
        n+=hookAllExisting(cl,"com.fooview.android.gesture.FVCandidateAdapter","CANDIDATE",false);
        n+=hookAllExisting(cl,"com.fooview.android.gesture.FVCandidateHWAdapter","CANDIDATE",false);
        n+=hookAllExisting(cl,"com.fooview.android.gesture.ocrresult.OCRTextResultAdapter","OCR",false);
        n+=hookAllExisting(cl,"com.fooview.android.gesture.circleReco.FVMediaProjectionService","SCREENSHOT",false);
        n+=hookNamed(cl,"com.fooview.android.fooview.fvprocess.CircleAppContainer",new String[]{"t0","W","p0","Z","V","e0","K","f0","E"},"CIRCLE_CORE",true);
        n+=hookNamed(cl,"com.fooview.android.fooview.fvprocess.CircleGuideContainer",new String[]{"onTouchEvent","dispatchTouchEvent","onTouch","show","hide","reset","clear"},"CIRCLE_GUIDE",true);
        n+=hookNamed(cl,"android.gesture.GestureStore",new String[]{"recognize","addGesture","removeGesture"},"GESTURE_STORE",false);
        n+=hookPreferences(cl);
        n+=hookWindowManager(cl);
        n+=hookKnownRunnables(cl);
        HookLogTransport.log("INSTALL","hooks="+n+" module=2.2.5");
        emitSelfTest(cl);
        log(Log.INFO,TAG,"installed hooks="+n);
    }

    private void emitSelfTest(ClassLoader cl){
        String[] classes={
            "com.fooview.android.fooview.fvprocess.FooViewService",
            "com.fooview.android.fooview.fvprocess.FooViewService$c3",
            "com.fooview.android.fooview.fvprocess.FloatIconView",
            "com.fooview.android.gesture.GesturePanel",
            "com.fooview.android.fooview.fvprocess.CircleAppContainer",
            "com.fooview.android.fooview.fvprocess.CircleGuideContainer"
        };
        for(String cn:classes){Class<?> c=find(cl,cn);HookLogTransport.log("SELFTEST","class="+cn+" found="+(c!=null));}
        Class<?> s=find(cl,"com.fooview.android.fooview.fvprocess.FooViewService");
        if(s!=null){for(String m:new String[]{"Y1","y3","R4","q4","h0","i3","k3","l3"})HookLogTransport.log("SELFTEST","method=FooViewService."+m+" found="+hasMethod(s,m));}
        Class<?> c3=find(cl,"com.fooview.android.fooview.fvprocess.FooViewService$c3");
        if(c3!=null){for(String m:new String[]{"onTouch","b"})HookLogTransport.log("SELFTEST","method=FooViewService$c3."+m+" found="+hasMethod(c3,m));}
        Class<?> circle=find(cl,"com.fooview.android.fooview.fvprocess.CircleAppContainer");
        if(circle!=null){for(String m:new String[]{"t0","W","p0","Z","V","e0","K"})HookLogTransport.log("SELFTEST","method=CircleAppContainer."+m+" found="+hasMethod(circle,m));}
    }
    private static boolean hasMethod(Class<?> c,String n){for(Method m:c.getDeclaredMethods())if(m.getName().equals(n))return true;return false;}

    private int hookApplicationAttach(ClassLoader cl){
        try{Method m=android.app.Application.class.getDeclaredMethod("attach",Context.class);m.setAccessible(true);String key=m.toGenericString();if(!hooked.add(key))return 0;hook(m).intercept(chain->{Object r=chain.proceed();try{Object a=chain.getThisObject();Object base=chain.getArg(0);Context c=a instanceof Context?(Context)a:(base instanceof Context?(Context)base:null);if(c!=null){HookLogTransport.init(c);registerCommand(c);HookLogTransport.log("SESSION","FV Runtime Inspector 2.2.5 api=102 sdk="+Build.VERSION.SDK_INT+" target="+TARGET);}}catch(Throwable t){HookLogTransport.log("INIT_ERR",String.valueOf(t));}return r;});return 1;}catch(Throwable t){HookLogTransport.log("HOOK_ERR","Application.attach "+t);return 0;}
    }

    private int hookNamed(ClassLoader cl,String cn,String[] names,String cat,boolean snapshot){Class<?> c=find(cl,cn);if(c==null){HookLogTransport.log("MISS","class "+cn);return 0;}Set<String> ns=new HashSet<>(Arrays.asList(names));int n=0;for(Method m:c.getDeclaredMethods())if(ns.contains(m.getName()))n+=hookMethod(m,cat,snapshot);return n;}
    private int hookAllExisting(ClassLoader cl,String cn,String cat,boolean snapshot){Class<?> c=find(cl,cn);if(c==null)return 0;int n=0;for(Method m:c.getDeclaredMethods()){if(m.isSynthetic()||m.getName().equals("toString")||m.getName().equals("hashCode"))continue;n+=hookMethod(m,cat,snapshot);}return n;}
    private int hookMethod(Method m,String cat,boolean snapshot){String key=m.toGenericString();if(!hooked.add(key))return 0;try{m.setAccessible(true);hook(m).intercept(chain->{
        boolean enabled=enabled(cat); if(!enabled)return chain.proceed();
        List<?> args=chain.getArgs();
        String dc=m.getDeclaringClass().getName(), mn=m.getName();
        if(dc.endsWith("FooViewService$c3") && mn.equals("onTouch")) FvSessionTracker.onTouch(args);
        if(dc.endsWith("FooViewService$c3") && mn.equals("b")) {Integer code=firstInt(args); if(code!=null)FvSessionTracker.onCode(code,"b");}
        if(dc.endsWith("FooViewService") && mn.equals("Y1")) {Integer code=firstInt(args); if(code!=null)FvSessionTracker.onCode(code,"Y1");}
        if(cat.equals("TOUCH") && args.stream().anyMatch(x->x instanceof MotionEvent e && e.getActionMasked()==MotionEvent.ACTION_MOVE)){long now=android.os.SystemClock.uptimeMillis();if(now-lastMoveLog<16)return chain.proceed();lastMoveLog=now;}
        Object th=chain.getThisObject();Map<String,String> before=snapshot&&FvInspectorConfig.objectDiff?ObjectSnapshot.take(th):Map.of();long st=android.os.SystemClock.elapsedRealtimeNanos();
        HookLogTransport.log(cat,"ENTER "+HookFmt.member(m)+" args="+HookFmt.args(args)+" stack="+HookFmt.stack(6));
        Object result;try{result=chain.proceed();}catch(Throwable t){HookLogTransport.log(cat,"THROW "+HookFmt.member(m)+" "+t);throw t;}
        long us=(android.os.SystemClock.elapsedRealtimeNanos()-st)/1000;String diff=snapshot&&FvInspectorConfig.objectDiff?" diff="+ObjectSnapshot.diff(before,ObjectSnapshot.take(th)):"";
        HookLogTransport.log(cat,"EXIT "+HookFmt.member(m)+" result="+HookFmt.value(result)+" us="+us+diff);return result;
    });HookLogTransport.log("SELFTEST","hooked="+HookFmt.member(m));return 1;}catch(Throwable t){HookLogTransport.log("HOOK_ERR",key+" :: "+t);return 0;}}

    private static Integer firstInt(List<?> args){for(Object o:args)if(o instanceof Integer i)return i;return null;}
    private int hookPreferences(ClassLoader cl){if(!FvInspectorConfig.preferences)return 0;int n=0;try{Class<?> c=Class.forName("android.app.SharedPreferencesImpl");for(Method m:c.getDeclaredMethods()){String x=m.getName();if(x.equals("getInt")||x.equals("getLong")||x.equals("getFloat")||x.equals("getBoolean")||x.equals("getString")||x.equals("contains"))n+=hookMethod(m,"PREF",false);}}catch(Throwable t){HookLogTransport.log("HOOK_ERR","prefs "+t);}try{Class<?> c=Class.forName("android.app.SharedPreferencesImpl$EditorImpl");for(Method m:c.getDeclaredMethods())if(m.getName().startsWith("put")||m.getName().equals("remove")||m.getName().equals("clear")||m.getName().equals("apply")||m.getName().equals("commit"))n+=hookMethod(m,"PREF_WRITE",false);}catch(Throwable t){HookLogTransport.log("HOOK_ERR","editor "+t);}return n;}
    private int hookWindowManager(ClassLoader cl){int n=0;try{Class<?> c=Class.forName("android.view.WindowManagerGlobal");for(Method m:c.getDeclaredMethods())if(m.getName().equals("addView")||m.getName().equals("updateViewLayout")||m.getName().equals("removeView")||m.getName().equals("removeViewImmediate"))n+=hookMethod(m,"WINDOW",false);}catch(Throwable t){HookLogTransport.log("HOOK_ERR","window "+t);}return n;}
    private int hookKnownRunnables(ClassLoader cl){int n=0;String[] cs={"com.fooview.android.fooview.fvprocess.FooViewService$q","com.fooview.android.fooview.fvprocess.FooViewService$c3$a","com.fooview.android.fooview.fvprocess.FooViewService$c3$b","com.fooview.android.fooview.fvprocess.FooViewService$c3$c","com.fooview.android.fooview.fvprocess.FooViewService$c3$d","com.fooview.android.fooview.fvprocess.FooViewService$c3$f"};for(String cn:cs)n+=hookNamed(cl,cn,new String[]{"run"},"RUNNABLE",true);return n;}
    private boolean enabled(String cat){return switch(cat){case "TOUCH"->FvInspectorConfig.touch;case "GESTURE"->FvInspectorConfig.gesture;case "POSITION"->FvInspectorConfig.position;case "PREF","PREF_WRITE"->FvInspectorConfig.preferences;case "WINDOW"->FvInspectorConfig.window;case "SCREENSHOT"->FvInspectorConfig.screenshot;case "OCR","CANDIDATE","CIRCLE_CORE","CIRCLE_GUIDE"->FvInspectorConfig.circleOcr;case "GESTURE_STORE"->FvInspectorConfig.gesture;default->true;};}
    private void registerCommand(Context c){try{IntentFilter f=new IntentFilter(HookLogTransport.ACTION_CMD);BroadcastReceiver r=new BroadcastReceiver(){@Override public void onReceive(Context x,Intent i){String cmd=i.getStringExtra("cmd");if("probe_on".equals(cmd)){FvInspectorConfig.methodProbe=true;installProbe(c.getClassLoader());}else if("probe_off".equals(cmd))FvInspectorConfig.methodProbe=false;else if("all_on".equals(cmd))FvInspectorConfig.setAll(true);else if("all_off".equals(cmd))FvInspectorConfig.setAll(false);else if("selftest".equals(cmd))emitSelfTest(c.getClassLoader());HookLogTransport.log("CMD","cmd="+cmd);}};if(Build.VERSION.SDK_INT>=33)c.registerReceiver(r,f,Context.RECEIVER_EXPORTED);else c.registerReceiver(r,f);}catch(Throwable t){HookLogTransport.log("CMD_ERR",String.valueOf(t));}}
    private void installProbe(ClassLoader cl){if(!FvInspectorConfig.methodProbe)return;String[] cs={"com.fooview.android.fooview.fvprocess.FooViewService","com.fooview.android.fooview.fvprocess.FooViewService$c3","com.fooview.android.fooview.fvprocess.FloatIconView","com.fooview.android.gesture.GesturePanel","com.fooview.android.fooview.settings.FooGestureSetting","com.fooview.android.fooview.fvprocess.CircleAppContainer","com.fooview.android.fooview.fvprocess.CircleGuideContainer"};int n=0;for(String cn:cs)n+=hookAllExisting(cl,cn,"PROBE",false);HookLogTransport.log("PROBE","installed="+n);}
    private static Class<?> find(ClassLoader cl,String n){try{return Class.forName(n,false,cl);}catch(Throwable t){return null;}}
}
