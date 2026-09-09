package com.yagay.floatlens;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class InspectorLog {
    private static final Object LOCK=new Object();
    private static final String FILE="fv-runtime-inspector.log";
    private static final long MAX=8L*1024L*1024L;
    static void append(Context c,String payload){if(c==null||payload==null||payload.isBlank())return;synchronized(LOCK){try{File f=new File(c.getFilesDir(),FILE);if(f.length()>MAX)rotate(f);try(FileOutputStream o=new FileOutputStream(f,true)){o.write(payload.getBytes(StandardCharsets.UTF_8));if(!payload.endsWith("\n"))o.write('\n');}}catch(Throwable ignored){}}}
    public static String read(Context c){synchronized(LOCK){try{File f=new File(c.getFilesDir(),FILE);return f.exists()?java.nio.file.Files.readString(f.toPath()):"";}catch(Throwable t){return "读取失败: "+t;}}}
    public static void clear(Context c){synchronized(LOCK){for(String n:new String[]{FILE,FILE+".old"}){File f=new File(c.getFilesDir(),n);if(f.exists())f.delete();}}}

    public static String selfTestStatus(Context c){
        String s=read(c);
        if(s.isBlank()) return "FV Hook：尚未收到 fooView 运行时日志";
        boolean target=s.contains("[SELFTEST] target_loaded=true");
        boolean service=s.contains("class=com.fooview.android.fooview.fvprocess.FooViewService found=true");
        boolean c3=s.contains("class=com.fooview.android.fooview.fvprocess.FooViewService$c3 found=true");
        boolean panel=s.contains("class=com.fooview.android.gesture.GesturePanel found=true");
        boolean onTouch=s.contains("method=FooViewService$c3.onTouch found=true");
        boolean y1=s.contains("method=FooViewService.Y1 found=true");
        int runtime=countRuntimeEvents(s);
        StringBuilder b=new StringBuilder();
        b.append("FV Hook 自检\n")
         .append("target loaded: ").append(target).append('\n')
         .append("FooViewService: ").append(service).append('\n')
         .append("FooViewService$c3: ").append(c3).append('\n')
         .append("GesturePanel: ").append(panel).append('\n')
         .append("c3.onTouch: ").append(onTouch).append('\n')
         .append("FooViewService.Y1: ").append(y1).append('\n')
         .append("runtime events: ").append(runtime);
        return b.toString();
    }

    private static int countRuntimeEvents(String s){int n=0;for(String l:s.split("\n")){if(l.contains("[TOUCH]")||l.contains("[SERVICE]")||l.contains("[GESTURE]")||l.contains("[POSITION]")||l.contains("[WINDOW]")||l.contains("[PREF]")||l.contains("[SCREENSHOT]")||l.contains("[OCR]")||l.contains("[CIRCLE]"))n++;}return n;}

    public static void exportZip(Context c,OutputStream out)throws IOException{try(ZipOutputStream z=new ZipOutputStream(out)){put(z,"fv-runtime.log",read(c));String old=readFile(new File(c.getFilesDir(),FILE+".old"));if(!old.isBlank())put(z,"fv-runtime.old.log",old);String d=DiagnosticLog.read(c);if(!d.isBlank())put(z,"floatlens-diagnostic.log",d);put(z,"selftest.txt",selfTestStatus(c));put(z,"summary.txt",summary(c));}}
    private static String summary(Context c){String s=read(c);Map<String,Integer> m=new TreeMap<>();for(String l:s.split("\n")){int a=l.indexOf('['),b=l.indexOf(']',a+1);if(a>=0&&b>a){String k=l.substring(a+1,b);m.put(k,m.getOrDefault(k,0)+1);}}StringBuilder o=new StringBuilder();o.append("FloatLens FV Runtime Inspector\nversion=").append(BuildConfig.VERSION_NAME).append("\nlines=").append(s.isBlank()?0:s.split("\n").length).append("\n\n").append(selfTestStatus(c)).append("\n\nCategory counts:\n");m.forEach((k,v)->o.append(k).append('=').append(v).append('\n'));return o.toString();}
    private static void put(ZipOutputStream z,String n,String s)throws IOException{z.putNextEntry(new ZipEntry(n));z.write(s.getBytes(StandardCharsets.UTF_8));z.closeEntry();}
    private static String readFile(File f){try{return f.exists()?java.nio.file.Files.readString(f.toPath()):"";}catch(Throwable t){return "";}}
    private static void rotate(File f)throws IOException{File old=new File(f.getParentFile(),FILE+".old");if(old.exists())old.delete();if(!f.renameTo(old))try(FileOutputStream o=new FileOutputStream(f,false)){} }
    private InspectorLog(){}
}
