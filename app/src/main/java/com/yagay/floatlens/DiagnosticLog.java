package com.yagay.floatlens;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Lightweight persistent diagnostic logger for FV behaviour calibration. */
public final class DiagnosticLog {
    private static final Object LOCK = new Object();
    private static final String FILE = "floatlens-fv-diagnostic.log";
    private static final long MAX_BYTES = 2L * 1024L * 1024L;
    private static Context app;

    public static void init(Context c) { if (c != null) app = c.getApplicationContext(); }
    public static boolean enabled(Context c) {
        Context x = c != null ? c.getApplicationContext() : app;
        return x != null && x.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE)
                .getBoolean(FloatSettings.K_DIAGNOSTIC, false);
    }
    public static void i(Context c, String tag, String msg) {
        Context x = c != null ? c.getApplicationContext() : app;
        if (x == null || !enabled(x)) return;
        init(x);
        synchronized (LOCK) {
            try {
                File f = file(x);
                if (f.length() > MAX_BYTES) rotate(f);
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                String line = ts + " +" + SystemClock.uptimeMillis() + "ms [" + tag + "] " + msg + "\n";
                try (FileOutputStream out = new FileOutputStream(f, true)) { out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            } catch (Throwable ignored) {}
        }
    }
    public static void sessionHeader(Context c) {
        Context x=c!=null?c.getApplicationContext():app; if(x==null||!enabled(x))return;
        i(x,"SESSION","FloatLens="+BuildConfig.VERSION_NAME+" sdk="+Build.VERSION.SDK_INT+" device="+Build.MANUFACTURER+"/"+Build.MODEL+" fingerprint="+Build.FINGERPRINT);
    }
    public static String read(Context c) {
        Context x=c!=null?c.getApplicationContext():app; if(x==null)return "";
        synchronized (LOCK) { try { File f=file(x); if(!f.exists())return ""; return java.nio.file.Files.readString(f.toPath()); } catch(Throwable t){return "读取日志失败: "+t;} }
    }
    public static void clear(Context c) { Context x=c!=null?c.getApplicationContext():app; if(x==null)return; synchronized(LOCK){try{File f=file(x);if(f.exists())f.delete();}catch(Throwable ignored){}} }
    private static File file(Context c){return new File(c.getFilesDir(),FILE);}    
    private static void rotate(File f) throws IOException { File old=new File(f.getParentFile(),FILE+".old"); if(old.exists())old.delete(); if(!f.renameTo(old)){try(FileOutputStream o=new FileOutputStream(f,false)){}} }
    private DiagnosticLog(){}
}
