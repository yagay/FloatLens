package com.yagay.floatlens.hook;

import java.lang.reflect.*;
import java.util.*;

final class ObjectSnapshot {
    static Map<String,String> take(Object o){
        LinkedHashMap<String,String> out=new LinkedHashMap<>(); if(o==null)return out;
        Class<?> c=o.getClass(); int depth=0;
        while(c!=null && c!=Object.class && depth++<3){
            for(Field f:c.getDeclaredFields()){
                if(Modifier.isStatic(f.getModifiers()))continue;
                Class<?> t=f.getType(); if(!(t.isPrimitive()||Number.class.isAssignableFrom(t)||t==String.class||t==Boolean.class||t.getName().contains("Point")||t.getName().contains("Rect")||t.getName().contains("FloatIconView")))continue;
                try{f.setAccessible(true);out.put(c.getSimpleName()+"."+f.getName(),HookFmt.value(f.get(o)));}catch(Throwable ignored){}
                if(out.size()>=80)return out;
            } c=c.getSuperclass();
        } return out;
    }
    static String diff(Map<String,String>a,Map<String,String>b){StringBuilder s=new StringBuilder();Set<String> ks=new LinkedHashSet<>();ks.addAll(a.keySet());ks.addAll(b.keySet());for(String k:ks){String x=a.get(k),y=b.get(k);if(!Objects.equals(x,y))s.append(k).append(':').append(x).append("->").append(y).append(';');}return s.length()==0?"(no primitive diff)":s.toString();}
    private ObjectSnapshot(){}
}
