package com.yagay.floatlens.hook;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayDeque;

final class HookLogTransport {
    static final String ACTION_LOG="com.yagay.floatlens.FV_HOOK_LOG";
    static final String ACTION_CMD="com.yagay.floatlens.FV_HOOK_COMMAND";
    static final String TARGET="com.yagay.floatlens";
    private static final Object LOCK=new Object();
    private static final ArrayDeque<String> Q=new ArrayDeque<>();
    private static volatile Context context;
    private static Handler handler;
    private static boolean scheduled;

    static void init(Context c){ if(c!=null){context=c.getApplicationContext(); if(handler==null) handler=new Handler(Looper.getMainLooper());} }
    static void log(String cat,String msg){
        String line=System.currentTimeMillis()+" +"+SystemClock.uptimeMillis()+" ["+cat+"] "+trim(msg,3500);
        synchronized(LOCK){ if(Q.size()>300) Q.pollFirst(); Q.addLast(line); if(!scheduled){scheduled=true; getHandler().postDelayed(HookLogTransport::flush,250);} }
    }
    static void flush(){
        StringBuilder b=new StringBuilder(); int n=0;
        synchronized(LOCK){ scheduled=false; while(!Q.isEmpty() && n<40){String s=Q.pollFirst(); if(b.length()+s.length()>24000){Q.addFirst(s);break;} b.append(s).append('\n'); n++;} if(!Q.isEmpty()){scheduled=true; getHandler().postDelayed(HookLogTransport::flush,250);} }
        if(b.length()==0)return;
        try{Context c=getContext(); if(c==null)return; Intent i=new Intent(ACTION_LOG).setPackage(TARGET); i.putExtra("payload",b.toString()); c.sendBroadcast(i);}catch(Throwable ignored){}
    }
    private static Handler getHandler(){if(handler==null)handler=new Handler(Looper.getMainLooper());return handler;}
    private static Context getContext(){Context c=context;if(c!=null)return c;try{Class<?> at=Class.forName("android.app.ActivityThread");Object a=at.getDeclaredMethod("currentApplication").invoke(null);if(a instanceof Context c2){init(c2);return c2;}return null;}catch(Throwable t){return null;}}
    private static String trim(String s,int n){if(s==null)return "null";s=s.replace('\n',' ');return s.length()<=n?s:s.substring(0,n)+"…";}
    private HookLogTransport(){}
}
