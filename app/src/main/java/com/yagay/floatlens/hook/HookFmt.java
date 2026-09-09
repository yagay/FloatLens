package com.yagay.floatlens.hook;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.*;
import java.util.*;

final class HookFmt {
    static String args(List<?> a){StringBuilder b=new StringBuilder("[");for(int i=0;i<a.size();i++){if(i>0)b.append(',');b.append(value(a.get(i)));}return b.append(']').toString();}
    static String value(Object o){
        if(o==null)return "null";
        if(o instanceof MotionEvent e)return "MotionEvent{"+action(e.getActionMasked())+" x="+f(e.getX())+" y="+f(e.getY())+" raw="+f(e.getRawX())+","+f(e.getRawY())+" pc="+e.getPointerCount()+" t="+e.getEventTime()+"}";
        if(o instanceof WindowManager.LayoutParams p)return "LP{x="+p.x+" y="+p.y+" w="+p.width+" h="+p.height+" type="+p.type+" flags=0x"+Integer.toHexString(p.flags)+" grav="+p.gravity+" alpha="+p.alpha+"}";
        if(o instanceof View v)return v.getClass().getName()+"{"+v.getWidth()+"x"+v.getHeight()+" xy="+f(v.getX())+","+f(v.getY())+" vis="+v.getVisibility()+"}";
        if(o instanceof Rect r)return r.toShortString();
        Class<?> c=o.getClass(); if(c.isArray())return c.getComponentType().getSimpleName()+"["+Array.getLength(o)+"]";
        if(o instanceof Number||o instanceof Boolean||o instanceof CharSequence||o instanceof Enum<?>)return String.valueOf(o);
        return c.getName()+"@"+Integer.toHexString(System.identityHashCode(o));
    }
    static String member(Member m){return m.getDeclaringClass().getName()+"#"+m.getName();}
    static String stack(int max){StackTraceElement[] st=Thread.currentThread().getStackTrace();StringBuilder b=new StringBuilder();int n=0;for(StackTraceElement e:st){String c=e.getClassName();if(c.startsWith("com.yagay.floatlens.hook")||c.startsWith("java.lang.Thread"))continue;if(c.startsWith("com.fooview")||c.startsWith("android.")){if(n++>0)b.append(" <- ");b.append(c).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());if(n>=max)break;}}return b.toString();}
    static String action(int a){return switch(a){case 0->"DOWN";case 1->"UP";case 2->"MOVE";case 3->"CANCEL";case 5->"POINTER_DOWN";case 6->"POINTER_UP";default->String.valueOf(a);};}
    private static String f(float v){return String.format(Locale.US,"%.1f",v);}
    private HookFmt(){}
}
