package com.yagay.floatlens;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Lightweight persistent diagnostic logger for FV behaviour calibration. */
public final class DiagnosticLog {
    private static final Object LOCK = new Object();
    private static final String FILE = "floatlens-fv-diagnostic.log";
    private static final long MAX_BYTES = 2L * 1024L * 1024L;
    // MOVE diagnostics used to write synchronously for almost every MotionEvent. On video pages that
    // main-thread file I/O was enough to make the floating icon visibly stutter. Keep diagnostic
    // coverage, but sample only the high-frequency movement records; state transitions remain exact.
    private static final long HOT_LOG_INTERVAL_MS = 90L;
    private static final Map<String, Long> HOT_LAST = new HashMap<>();
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
        long now = SystemClock.uptimeMillis();
        String hotKey = hotKey(tag, msg);
        if (hotKey != null) {
            synchronized (HOT_LAST) {
                long last = HOT_LAST.getOrDefault(hotKey, 0L);
                if (now - last < HOT_LOG_INTERVAL_MS) return;
                HOT_LAST.put(hotKey, now);
            }
        }
        synchronized (LOCK) {
            try {
                File f = file(x);
                if (f.length() > MAX_BYTES) rotate(f);
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                String line = ts + " +" + now + "ms [" + tag + "] " + msg + "\n";
                try (FileOutputStream out = new FileOutputStream(f, true)) { out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            } catch (Throwable ignored) {}
        }
    }
    private static String hotKey(String tag, String msg) {
        if (tag == null) return null;
        if ("TOUCH".equals(tag)) return "TOUCH";
        if ("FV_PROBE".equals(tag) && (msg == null || !msg.startsWith("BEGIN"))) return "FV_PROBE";
        if ("FV_PROBE_VIEW".equals(tag) && msg != null && msg.startsWith("MOVE")) return "FV_PROBE_VIEW_MOVE";
        if ("FV_OP_HINT".equals(tag) && msg != null && msg.startsWith("mode=")) return "FV_OP_HINT_MOVE";
        if ("FV_DIRECT".equals(tag) && msg != null && msg.startsWith("REARM")) return "FV_DIRECT_REARM";
        return null;
    }
    public static void sessionHeader(Context c) {
        Context x=c!=null?c.getApplicationContext():app; if(x==null||!enabled(x))return;
        i(x,"SESSION","FloatLens="+BuildConfig.VERSION_NAME+" sdk="+Build.VERSION.SDK_INT+" device="+Build.MANUFACTURER+"/"+Build.MODEL+" fingerprint="+Build.FINGERPRINT);
    }
    public static String read(Context c) {
        Context x=c!=null?c.getApplicationContext():app; if(x==null)return "";
        synchronized (LOCK) { try { File f=file(x); if(!f.exists())return ""; return java.nio.file.Files.readString(f.toPath()); } catch(Throwable t){return "读取日志失败: "+t;} }
    }
    public static void clear(Context c) {
        Context x=c!=null?c.getApplicationContext():app; if(x==null)return;
        synchronized(HOT_LAST){HOT_LAST.clear();}
        synchronized(LOCK){try{File f=file(x);if(f.exists())f.delete();}catch(Throwable ignored){}}
    }
    private static File file(Context c){return new File(c.getFilesDir(),FILE);}
    private static void rotate(File f) throws IOException { File old=new File(f.getParentFile(),FILE+".old"); if(old.exists())old.delete(); if(!f.renameTo(old)){try(FileOutputStream o=new FileOutputStream(f,false)){}} }
    private DiagnosticLog(){}
}
