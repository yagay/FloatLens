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
    private static final int STATUS_TAIL=768*1024; // never parse multi-MiB logs on the UI thread

    static void append(Context c,String payload){if(c==null||payload==null||payload.isBlank())return;synchronized(LOCK){try{File f=new File(c.getFilesDir(),FILE);if(f.length()>MAX)rotate(c);try(FileOutputStream o=new FileOutputStream(f,true)){o.write(payload.getBytes(StandardCharsets.UTF_8));if(!payload.endsWith("\n"))o.write('\n');}}catch(Throwable ignored){}}}
    public static String read(Context c){synchronized(LOCK){return readFile(new File(c.getFilesDir(),FILE));}}
    public static boolean hasAny(Context c){synchronized(LOCK){for(int i=0;i<=ROTATIONS;i++){File f=new File(c.getFilesDir(),i==0?FILE:FILE+"."+i);if(f.exists()&&f.length()>0)return true;}return false;}}
    public static void clear(Context c){synchronized(LOCK){for(int i=0;i<=ROTATIONS;i++){File f=new File(c.getFilesDir(),i==0?FILE:FILE+"."+i);if(f.exists())f.delete();}}}

    public static String selfTestStatus(Context c){
        String s;
        synchronized(LOCK){
            File cur=new File(c.getFilesDir(),FILE);
            s=readTail(cur,STATUS_TAIL);
            if(s.isBlank()){
                for(int i=1;i<=ROTATIONS&&s.isBlank();i++)s=readTail(new File(c.getFilesDir(),FILE+"."+i),STATUS_TAIL);
            }
        }
        if(s.isBlank())return "FV Hook：尚未收到 fooView 运行时日志";
        boolean target=s.contains("[SELFTEST] target_loaded=true");
        boolean service=s.contains("class=com.fooview.android.fooview.fvprocess.FooViewService found=true");
        boolean c3=s.contains("class=com.fooview.android.fooview.fvprocess.FooViewService$c3 found=true");
        boolean panel=s.contains("class=com.fooview.android.gesture.GesturePanel found=true");
        boolean onTouch=s.contains("method=FooViewService$c3.onTouch found=true")||s.contains("hooked=com.fooview.android.fooview.fvprocess.FooViewService$c3#onTouch");
        boolean y1=s.contains("method=FooViewService.Y1 found=true")||s.contains("hooked=com.fooview.android.fooview.fvprocess.FooViewService#Y1");
        boolean full=s.contains("mode=FULL_CAPTURE")||s.contains("module=2.3.0-full");
        int runtime=countRuntimeEvents(s);
        return "FV Hook 自检（仅扫描日志尾部，避免大日志导致闪退）\nfull capture: "+full+"\ntarget loaded: "+target+"\nFooViewService: "+service+"\nFooViewService$c3: "+c3+"\nGesturePanel: "+panel+"\nc3.onTouch: "+onTouch+"\nFooViewService.Y1: "+y1+"\nruntime events in tail: "+runtime;
    }
    private static int countRuntimeEvents(String s){int n=0;for(String l:s.split("\n"))if(l.matches(".*\\[(TOUCH|SERVICE|GESTURE|POSITION|WINDOW|PREF|PREF_WRITE|SCREENSHOT|OCR|CANDIDATE|CIRCLE_CORE|CIRCLE_GUIDE|FULL_CAPTURE|FULL_INNER|ACTION_LAYER|ACTION_DOWNSTREAM|TIMING|SESSION_SUMMARY)\\].*"))n++;return n;}

    public static void exportZip(Context c,OutputStream out)throws IOException{
        try(ZipOutputStream z=new ZipOutputStream(out)){
            streamFile(z,"fv-runtime.log",new File(c.getFilesDir(),FILE));
            for(int i=1;i<=ROTATIONS;i++)streamFile(z,"fv-runtime."+i+".log",new File(c.getFilesDir(),FILE+"."+i));
            File diag=new File(c.getFilesDir(),"floatlens-diagnostic.log");
            if(diag.exists()&&diag.length()>0)streamFile(z,"floatlens-diagnostic.log",diag);
            put(z,"selftest.txt",selfTestStatus(c));
            put(z,"summary.txt",summary(c));
        }
    }
    private static String summary(Context c){
        long bytes=0, lines=0;
        Map<String,Integer> m=new TreeMap<>();
        synchronized(LOCK){
            for(int i=ROTATIONS;i>=0;i--){
                File f=new File(c.getFilesDir(),i==0?FILE:FILE+"."+i);
                if(!f.exists())continue;
                bytes+=f.length();
                try(BufferedReader br=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8),64*1024)){
                    String l; while((l=br.readLine())!=null){lines++;int a=l.indexOf('['),b=l.indexOf(']',a+1);if(a>=0&&b>a){String k=l.substring(a+1,b);m.put(k,m.getOrDefault(k,0)+1);}}
                }catch(Throwable ignored){}
            }
        }
        StringBuilder o=new StringBuilder();
        o.append("FloatLens FV Runtime Inspector FULL CAPTURE\nversion=").append(BuildConfig.VERSION_NAME)
         .append("\nretention≈64MiB\nbytes=").append(bytes).append("\nlines=").append(lines)
         .append("\n\n").append(selfTestStatus(c)).append("\n\nCategory counts:\n");
        m.forEach((k,v)->o.append(k).append('=').append(v).append('\n'));
        return o.toString();
    }
    private static void streamFile(ZipOutputStream z,String name,File f)throws IOException{if(!f.exists()||f.length()==0)return;z.putNextEntry(new ZipEntry(name));try(InputStream in=new BufferedInputStream(new FileInputStream(f),64*1024)){byte[] buf=new byte[64*1024];int n;while((n=in.read(buf))>0)z.write(buf,0,n);}z.closeEntry();}
    private static void put(ZipOutputStream z,String n,String s)throws IOException{z.putNextEntry(new ZipEntry(n));z.write(s.getBytes(StandardCharsets.UTF_8));z.closeEntry();}
    private static String readFile(File f){try{return f.exists()?java.nio.file.Files.readString(f.toPath()):"";}catch(Throwable t){return "";}}
    private static String readTail(File f,int maxBytes){if(f==null||!f.exists()||f.length()==0)return "";try(RandomAccessFile r=new RandomAccessFile(f,"r")){long len=r.length();int n=(int)Math.min((long)maxBytes,len);byte[] b=new byte[n];r.seek(len-n);r.readFully(b);return new String(b,StandardCharsets.UTF_8);}catch(Throwable t){return "";}}
    private static void rotate(Context c)throws IOException{File dir=c.getFilesDir();File oldest=new File(dir,FILE+"."+ROTATIONS);if(oldest.exists())oldest.delete();for(int i=ROTATIONS-1;i>=1;i--){File src=new File(dir,FILE+"."+i),dst=new File(dir,FILE+"."+(i+1));if(src.exists())src.renameTo(dst);}File cur=new File(dir,FILE),one=new File(dir,FILE+".1");if(cur.exists()&&!cur.renameTo(one))try(FileOutputStream o=new FileOutputStream(cur,false)){} }
    private InspectorLog(){}
}
