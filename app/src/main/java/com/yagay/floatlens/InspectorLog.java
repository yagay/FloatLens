package com.yagay.floatlens;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class InspectorLog {
    private static final Object LOCK=new Object();
    private static final String FILE="fv-runtime-inspector.log";
    private static final long MAX=16L*1024L*1024L;
    private static final int ROTATIONS=3; // current + 3 rotated ~= 64 MiB total

    static void append(Context c,String payload){if(c==null||payload==null||payload.isBlank())return;synchronized(LOCK){try{File f=new File(c.getFilesDir(),FILE);if(f.length()>MAX)rotate(c);try(FileOutputStream o=new FileOutputStream(f,true)){o.write(payload.getBytes(StandardCharsets.UTF_8));if(!payload.endsWith("\n"))o.write('\n');}}catch(Throwable ignored){}}}
    public static String read(Context c){synchronized(LOCK){return readFile(new File(c.getFilesDir(),FILE));}}
    public static void clear(Context c){synchronized(LOCK){for(int i=0;i<=ROTATIONS;i++){File f=new File(c.getFilesDir(),i==0?FILE:FILE+"."+i);if(f.exists())f.delete();}}}

    public static String selfTestStatus(Context c){String s=read(c);if(s.isBlank())return "FV Hook：尚未收到 fooView 运行时日志";boolean target=s.contains("[SELFTEST] target_loaded=true");boolean service=s.contains("class=com.fooview.android.fooview.fvprocess.FooViewService found=true");boolean c3=s.contains("class=com.fooview.android.fooview.fvprocess.FooViewService$c3 found=true");boolean panel=s.contains("class=com.fooview.android.gesture.GesturePanel found=true");boolean onTouch=s.contains("method=FooViewService$c3.onTouch found=true")||s.contains("hooked=com.fooview.android.fooview.fvprocess.FooViewService$c3#onTouch");boolean y1=s.contains("method=FooViewService.Y1 found=true")||s.contains("hooked=com.fooview.android.fooview.fvprocess.FooViewService#Y1");boolean full=s.contains("mode=FULL_CAPTURE")||s.contains("module=2.3.0-full");int runtime=countRuntimeEvents(s);return "FV Hook 自检\nfull capture: "+full+"\ntarget loaded: "+target+"\nFooViewService: "+service+"\nFooViewService$c3: "+c3+"\nGesturePanel: "+panel+"\nc3.onTouch: "+onTouch+"\nFooViewService.Y1: "+y1+"\nruntime events: "+runtime;}
    private static int countRuntimeEvents(String s){int n=0;for(String l:s.split("\n"))if(l.matches(".*\\[(TOUCH|SERVICE|GESTURE|POSITION|WINDOW|PREF|PREF_WRITE|SCREENSHOT|OCR|CANDIDATE|CIRCLE_CORE|CIRCLE_GUIDE|FULL_CAPTURE|FULL_INNER|ACTION_LAYER|ACTION_DOWNSTREAM|TIMING|SESSION_SUMMARY)\\].*"))n++;return n;}

    public static void exportZip(Context c,OutputStream out)throws IOException{try(ZipOutputStream z=new ZipOutputStream(out)){put(z,"fv-runtime.log",read(c));for(int i=1;i<=ROTATIONS;i++){String old=readFile(new File(c.getFilesDir(),FILE+"."+i));if(!old.isBlank())put(z,"fv-runtime."+i+".log",old);}String d=DiagnosticLog.read(c);if(!d.isBlank())put(z,"floatlens-diagnostic.log",d);put(z,"selftest.txt",selfTestStatus(c));put(z,"summary.txt",summary(c));}}
    private static String summary(Context c){StringBuilder all=new StringBuilder();for(int i=ROTATIONS;i>=1;i--)all.append(readFile(new File(c.getFilesDir(),FILE+"."+i)));all.append(read(c));String s=all.toString();Map<String,Integer> m=new TreeMap<>();for(String l:s.split("\n")){int a=l.indexOf('['),b=l.indexOf(']',a+1);if(a>=0&&b>a){String k=l.substring(a+1,b);m.put(k,m.getOrDefault(k,0)+1);}}StringBuilder o=new StringBuilder();o.append("FloatLens FV Runtime Inspector FULL CAPTURE\nversion=").append(BuildConfig.VERSION_NAME).append("\nretention≈64MiB\nlines=").append(s.isBlank()?0:s.split("\n").length).append("\n\n").append(selfTestStatus(c)).append("\n\nCategory counts:\n");m.forEach((k,v)->o.append(k).append('=').append(v).append('\n'));return o.toString();}
    private static void put(ZipOutputStream z,String n,String s)throws IOException{z.putNextEntry(new ZipEntry(n));z.write(s.getBytes(StandardCharsets.UTF_8));z.closeEntry();}
    private static String readFile(File f){try{return f.exists()?java.nio.file.Files.readString(f.toPath()):"";}catch(Throwable t){return "";}}
    private static void rotate(Context c)throws IOException{File dir=c.getFilesDir();File oldest=new File(dir,FILE+"."+ROTATIONS);if(oldest.exists())oldest.delete();for(int i=ROTATIONS-1;i>=1;i--){File src=new File(dir,FILE+"."+i),dst=new File(dir,FILE+"."+(i+1));if(src.exists())src.renameTo(dst);}File cur=new File(dir,FILE),one=new File(dir,FILE+".1");if(cur.exists()&&!cur.renameTo(one))try(FileOutputStream o=new FileOutputStream(cur,false)){} }
    private InspectorLog(){}
}
