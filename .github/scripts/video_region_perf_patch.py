from pathlib import Path

base = Path('app/src/main/java/com/yagay/floatlens')

# FloatService: keep the input-owning icon window compact and coalesce drag window moves.
p = base / 'FloatService.java'
s = p.read_text()
if 'primaryMoveUpdatePosted' not in s:
    s = s.replace('    private boolean positionMoveArmed;\n',
                  '    private boolean positionMoveArmed;\n    private boolean primaryMoveUpdatePosted,secondaryMoveUpdatePosted;\n')
s = s.replace('                safeUpdate(mirrored?secondary:primary,lp);\n',
              '                scheduleMoveUpdate(mirrored?secondary:primary,lp);\n', 1)
s = s.replace('                    safeUpdate(secondary,secondaryLp);\n',
              '                    scheduleMoveUpdate(secondary,secondaryLp);\n', 1)
old = '''            @Override public void onDirectSelectionStart(){
                if(directExpanded[0])return;
                View icon=mirrored?secondary:primary;
                if(icon==null)return;
                directWindow[0]=lp.x;directWindow[1]=lp.y;directWindow[2]=lp.width;directWindow[3]=lp.height;
                directExpanded[0]=true;

                View other=mirrored?primary:secondary;
                if(other!=null){otherVisibility[0]=other.getVisibility();other.setVisibility(View.INVISIBLE);}

                lp.x=0;lp.y=0;
                lp.width=WindowManager.LayoutParams.MATCH_PARENT;
                lp.height=WindowManager.LayoutParams.MATCH_PARENT;
                safeUpdate(icon,lp);
                DiagnosticLog.i(FloatService.this,"FV_DIRECT","expand same icon fullscreen saved="
                        +directWindow[0]+","+directWindow[1]+" "+directWindow[2]+"x"+directWindow[3]);
            }

            @Override public void onDirectSelectionEnd(){
                if(!directExpanded[0])return;
                View icon=mirrored?secondary:primary;
                lp.x=directWindow[0];lp.y=directWindow[1];lp.width=directWindow[2];lp.height=directWindow[3];
                safeUpdate(icon,lp);

                View other=mirrored?primary:secondary;
                if(other!=null)other.setVisibility(otherVisibility[0]);
                directExpanded[0]=false;
                DiagnosticLog.i(FloatService.this,"FV_DIRECT","restore icon window="
                        +lp.x+","+lp.y+" "+lp.width+"x"+lp.height);
            }
'''
new = '''            @Override public void onDirectSelectionStart(){
                if(directExpanded[0])return;
                View icon=mirrored?secondary:primary;
                if(icon==null)return;
                directExpanded[0]=true;
                View other=mirrored?primary:secondary;
                if(other!=null){otherVisibility[0]=other.getVisibility();other.setVisibility(View.INVISIBLE);}
                DiagnosticLog.i(FloatService.this,"FV_DIRECT","keep compact touch owner window="
                        +lp.x+","+lp.y+" "+lp.width+"x"+lp.height);
            }

            @Override public void onDirectSelectionEnd(){
                if(!directExpanded[0])return;
                View other=mirrored?primary:secondary;
                if(other!=null)other.setVisibility(otherVisibility[0]);
                directExpanded[0]=false;
                DiagnosticLog.i(FloatService.this,"FV_DIRECT","compact touch owner end window="
                        +lp.x+","+lp.y+" "+lp.width+"x"+lp.height);
            }
'''
if old in s:
    s = s.replace(old, new, 1)
elif 'keep compact touch owner window=' not in s:
    raise SystemExit('FloatService direct block missing')
old = '    private void safeUpdate(View v,WindowManager.LayoutParams lp){if(v==null)return;try{wm.updateViewLayout(v,lp);}catch(Throwable ignored){}}\n'
new = '''    private void scheduleMoveUpdate(View v,WindowManager.LayoutParams lp){
        if(v==null)return;
        if(v==primary){
            if(primaryMoveUpdatePosted)return;
            primaryMoveUpdatePosted=true;
            v.postOnAnimation(()->{primaryMoveUpdatePosted=false;if(v==primary)safeUpdate(v,lp);});
            return;
        }
        if(v==secondary){
            if(secondaryMoveUpdatePosted)return;
            secondaryMoveUpdatePosted=true;
            v.postOnAnimation(()->{secondaryMoveUpdatePosted=false;if(v==secondary)safeUpdate(v,lp);});
            return;
        }
        safeUpdate(v,lp);
    }
    private void safeUpdate(View v,WindowManager.LayoutParams lp){if(v==null)return;try{wm.updateViewLayout(v,lp);}catch(Throwable ignored){}}
'''
if 'private void scheduleMoveUpdate' not in s:
    if old not in s: raise SystemExit('FloatService safeUpdate marker missing')
    s = s.replace(old, new, 1)
s = s.replace('    private void removeIcons(){trail.end();removeWakeViews();',
              '    private void removeIcons(){primaryMoveUpdatePosted=secondaryMoveUpdatePosted=false;trail.end();removeWakeViews();')
p.write_text(s)

# ViewHoverOverlay: point-specific Accessibility collection and lightweight region frame.
p = base / 'ViewHoverOverlay.java'
s = p.read_text()
s = s.replace('    private static final long POINT_SCAN_MIN_MS = 24L;\n',
              '    private static final long POINT_SCAN_MIN_MS = 32L;\n')
if 'private FvRegionFrameOverlay regionFrame;' not in s:
    s = s.replace('    private HoverView view;\n',
                  '    private HoverView view;\n    private FvRegionFrameOverlay regionFrame;\n')
old = '''    public void begin() {
        if (accessibility == null) return;
        refreshAccessibilityTree(true);
    }
'''
new = '''    public void begin() {
        // Point-specific collection happens in update(); avoid a whole-tree scan just for arming.
    }
'''
if old in s: s = s.replace(old, new, 1)
old = '''            directRegionMode = true;
            current = null;
            confirmed = false;
            ensureView();
            if (view != null) view.setCandidate(null);
            DiagnosticLog.i(context, "FV_REGION", "ENTER dx=" + Math.round(dx)
                    + " dy=" + Math.round(dy));
'''
new = '''            directRegionMode = true;
            current = null;
            confirmed = false;
            detachView();
            if (regionFrame == null) regionFrame = new FvRegionFrameOverlay(context);
            DiagnosticLog.i(context, "FV_REGION", "ENTER lightweight-frame dx=" + Math.round(dx)
                    + " dy=" + Math.round(dy));
'''
if old in s: s = s.replace(old, new, 1)
elif 'ENTER lightweight-frame' not in s: raise SystemExit('ViewHover enter marker missing')
old = '''                directRegion.set(l, t, r, b);
                ensureView();
                if (view != null) view.setDirectState(true, directRegion);
'''
new = '''                directRegion.set(l, t, r, b);
                if (regionFrame == null) regionFrame = new FvRegionFrameOverlay(context);
                regionFrame.show(directRegion);
'''
if old in s: s = s.replace(old, new, 1)
elif 'regionFrame.show(directRegion)' not in s: raise SystemExit('ViewHover region update marker missing')
old = '''        refreshAccessibilityTree(false);
        ScreenCandidate next = model.selectAt(selectionX, selectionY);
'''
new = '''        try {
            model.setAccessibility(AccessibilityCandidateCollector.collectAtPoint(
                    accessibility, selectionX, selectionY));
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_TREE", "point refresh failed=" + t);
            model.setAccessibility(Collections.emptyList());
        }
        ScreenCandidate next = model.selectAt(selectionX, selectionY);
'''
if old in s: s = s.replace(old, new, 1)
elif 'collectAtPoint(' not in s: raise SystemExit('ViewHover point scan marker missing')
old = '''    private void close() {
        detachView();
        current = null;
'''
new = '''    private void close() {
        detachView();
        if (regionFrame != null) regionFrame.close();
        regionFrame = null;
        current = null;
'''
if old in s: s = s.replace(old, new, 1)
elif 'regionFrame.close()' not in s: raise SystemExit('ViewHover close marker missing')
p.write_text(s)

# AccessibilityCandidateCollector: prune traversal to only nodes containing the probe point.
p = base / 'AccessibilityCandidateCollector.java'
s = p.read_text()
marker = '    private static void collectNode(LensAccessibilityService service, AccessibilityNodeInfo n,\n'
insert = '''    public static List<ScreenCandidate> collectAtPoint(LensAccessibilityService service, float x, float y) {
        ArrayList<ScreenCandidate> out = new ArrayList<>();
        if (service == null) return out;
        Rect screen = service.screenBounds();
        int px = Math.round(x), py = Math.round(y);
        int[] count = {0};
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (int wi = 0; wi < windows.size(); wi++) {
                    AccessibilityWindowInfo w = windows.get(wi);
                    if (w == null) continue;
                    Rect wr = new Rect();
                    try { w.getBoundsInScreen(wr); } catch (Throwable ignored) {}
                    if (!wr.isEmpty() && !wr.contains(px, py)) continue;
                    AccessibilityNodeInfo root = null;
                    try { root = w.getRoot(); } catch (Throwable ignored) {}
                    if (root == null || service.getPackageName().equals(nodePackage(root))) continue;
                    collectNodeAtPoint(service, root, screen, px, py, 0, count, out);
                    if (count[0] > 2200) break;
                }
            }
            AccessibilityNodeInfo active = null;
            try { active = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
            if (active != null && !service.getPackageName().equals(nodePackage(active))) {
                collectNodeAtPoint(service, active, screen, px, py, 0, count, out);
            }
        } catch (Throwable t) {
            DiagnosticLog.i(service, "FV_TREE", "point collect failed=" + t);
        }
        return CandidateGeometryFilter.filter(out, screen);
    }

    private static void collectNodeAtPoint(LensAccessibilityService service, AccessibilityNodeInfo n,
                                           Rect screen, int px, int py, int depth, int[] count,
                                           List<ScreenCandidate> out) {
        if (n == null || depth > 80 || count[0]++ > 2200) return;
        try { if (!n.isVisibleToUser()) return; } catch (Throwable ignored) {}
        Rect r = new Rect();
        try { n.getBoundsInScreen(r); } catch (Throwable t) { return; }
        if (r.isEmpty()) return;
        Rect clipped = new Rect(r);
        if (screen != null && !screen.isEmpty() && !clipped.intersect(screen)) return;
        if (!clipped.contains(px, py)) return;

        String cls = safeClass(n);
        String id = safeId(n);
        String pkg = nodePackage(n);
        CharSequence ownText = firstNonBlank(
                safeText(n), safeContentDescription(n), safeHint(n), safeStateDescription(n));
        boolean image = isImageCandidate(service, n, clipped, cls, id);
        boolean fullscreen = isFullscreenLike(clipped, screen);
        if (image) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.NON_TEXT,
                    ScreenCandidate.Source.ACCESSIBILITY,
                    ownText == null ? "" : ownText.toString().trim(), cls, id, pkg, depth, false,
                    safeClickable(n), safeEditable(n), safeFocusable(n), true));
        } else if (ownText != null) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.TEXT,
                    ScreenCandidate.Source.ACCESSIBILITY, ownText.toString().trim(), cls, id, pkg,
                    depth, false, safeClickable(n), safeEditable(n), safeFocusable(n), false));
        } else if (fullscreen) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.ROOT,
                    ScreenCandidate.Source.ACCESSIBILITY, "", cls, id, pkg, depth, true,
                    safeClickable(n), safeEditable(n), safeFocusable(n), false));
        }

        int children = Math.min(300, safeChildCount(n));
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try { child = n.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collectNodeAtPoint(service, child, screen, px, py,
                    depth + 1, count, out);
        }
    }

'''
if 'collectAtPoint(LensAccessibilityService service' not in s:
    if marker not in s: raise SystemExit('AccessibilityCandidateCollector marker missing')
    s = s.replace(marker, insert + marker, 1)
p.write_text(s)

# SelectionPointTransformer: cache screen bounds for the gesture.
p = base / 'SelectionPointTransformer.java'
s = p.read_text()
if 'gestureScreen' not in s:
    s = s.replace('    private boolean initialized;\n    private long lastLogAt;\n',
                  '    private boolean initialized;\n    private final Rect gestureScreen = new Rect();\n    private long lastLogAt;\n')
    s = s.replace('        Rect screen = screenBounds();\n        gestureLeftSide = screen.isEmpty()\n',
                  '        Rect screen = currentScreenBounds();\n        gestureScreen.set(screen);\n        gestureLeftSide = screen.isEmpty()\n', 1)
    s = s.replace('        Rect screen = screenBounds();\n        float lead = dp(FV_EDGE_LEAD_DP);\n',
                  '        Rect screen = gestureScreen;\n        float lead = dp(FV_EDGE_LEAD_DP);\n', 1)
    s = s.replace('        Rect screen = screenBounds();\n        touchOffsetX = iconWidth / 2f;\n',
                  '        Rect screen = currentScreenBounds();\n        gestureScreen.set(screen);\n        touchOffsetX = iconWidth / 2f;\n', 1)
    s = s.replace('    private Rect screenBounds() {\n', '    private Rect currentScreenBounds() {\n')
p.write_text(s)

# FvProbePointOverlay: coalesce WindowManager moves to one per animation frame.
p = base / 'FvProbePointOverlay.java'
s = p.read_text()
if 'private boolean framePosted;' not in s:
    s = s.replace('    private boolean visible;\n    private State state = State.TRACKING_RED;\n',
                  '    private boolean visible;\n    private boolean framePosted;\n    private int targetX,targetY;\n    private final Runnable applyMove = this::applyPendingMove;\n    private State state = State.TRACKING_RED;\n')
old = '''    public PointF showAt(float screenX, float screenY) {
        if (!attached) attachHidden();
        lp.x = Math.round(screenX - sizePx / 2f);
        lp.y = Math.round(screenY - sizePx / 2f);
        if (attached) {
            try {
                wm.updateViewLayout(view, lp);
                if (!visible) {
                    visible = true;
                    view.setVisibility(View.VISIBLE);
                }
            } catch (Throwable t) {
                DiagnosticLog.i(context, "FV_PROBE_VIEW", "move failed=" + t);
            }
        }
        float cx = lp.x + sizePx / 2f;
        float cy = lp.y + sizePx / 2f;
        DiagnosticLog.i(context, "FV_PROBE_VIEW", "MOVE centre=" + Math.round(cx) + ","
                + Math.round(cy) + " window=" + lp.x + "," + lp.y + " state=" + state);
        return new PointF(cx, cy);
    }
'''
new = '''    public PointF showAt(float screenX, float screenY) {
        if (!attached) attachHidden();
        targetX = Math.round(screenX - sizePx / 2f);
        targetY = Math.round(screenY - sizePx / 2f);
        if (attached) {
            if (!visible) { visible = true; view.setVisibility(View.VISIBLE); }
            if (!framePosted) { framePosted = true; view.postOnAnimation(applyMove); }
        }
        float cx = targetX + sizePx / 2f;
        float cy = targetY + sizePx / 2f;
        DiagnosticLog.i(context, "FV_PROBE_VIEW", "MOVE centre=" + Math.round(cx) + ","
                + Math.round(cy) + " window=" + targetX + "," + targetY + " state=" + state);
        return new PointF(cx, cy);
    }

    private void applyPendingMove() {
        framePosted = false;
        if (!attached) return;
        if (lp.x == targetX && lp.y == targetY) return;
        lp.x = targetX; lp.y = targetY;
        try { wm.updateViewLayout(view, lp); }
        catch (Throwable t) { DiagnosticLog.i(context, "FV_PROBE_VIEW", "move failed=" + t); }
    }
'''
if old in s: s = s.replace(old, new, 1)
elif 'applyPendingMove()' not in s: raise SystemExit('Probe showAt marker missing')
if 'view.removeCallbacks(applyMove)' not in s:
    s = s.replace('        visible = false;\n        view.setVisibility(View.INVISIBLE);\n        lp.x = -sizePx;\n',
                  '        visible = false;\n        framePosted = false;\n        try { view.removeCallbacks(applyMove); } catch (Throwable ignored) {}\n        view.setVisibility(View.INVISIBLE);\n        lp.x = -sizePx;\n', 1)
    s = s.replace('    public void close() {\n        visible = false;\n',
                  '    public void close() {\n        visible = false;\n        framePosted = false;\n        try { view.removeCallbacks(applyMove); } catch (Throwable ignored) {}\n', 1)
p.write_text(s)

# FvOperationHintOverlay: same coalescing.
p = base / 'FvOperationHintOverlay.java'
s = p.read_text()
if 'private boolean framePosted;' not in s:
    s = s.replace('    private boolean visible;\n    private Mode mode = Mode.SCREENSHOT;\n',
                  '    private boolean visible;\n    private boolean framePosted;\n    private int targetX,targetY;\n    private final Runnable applyMove = this::applyPendingMove;\n    private Mode mode = Mode.SCREENSHOT;\n')
old = '''        lp.x = Math.round(gestureLeftSide
                ? iconBounds.left + iconBounds.width()
                : iconBounds.left - sizePx);
        lp.y = Math.round(iconBounds.top - sizePx);

        if (attached) {
            try {
                wm.updateViewLayout(view, lp);
                if (!visible) {
                    visible = true;
                    view.setVisibility(View.VISIBLE);
                }
            } catch (Throwable t) {
                DiagnosticLog.i(context, "FV_OP_HINT", "move failed=" + t);
            }
        }
        DiagnosticLog.i(context, "FV_OP_HINT", "mode=" + mode
                + " pos=" + lp.x + "," + lp.y
'''
new = '''        targetX = Math.round(gestureLeftSide
                ? iconBounds.left + iconBounds.width()
                : iconBounds.left - sizePx);
        targetY = Math.round(iconBounds.top - sizePx);

        if (attached) {
            if (!visible) { visible = true; view.setVisibility(View.VISIBLE); }
            if (!framePosted) { framePosted = true; view.postOnAnimation(applyMove); }
        }
        DiagnosticLog.i(context, "FV_OP_HINT", "mode=" + mode
                + " pos=" + targetX + "," + targetY
'''
if old in s: s = s.replace(old, new, 1)
elif 'targetX = Math.round(gestureLeftSide' not in s: raise SystemExit('Operation show marker missing')
if 'private void applyPendingMove()' not in s:
    marker = '    public void hide() {\n'
    method = '''    private void applyPendingMove() {
        framePosted = false;
        if (!attached) return;
        if (lp.x == targetX && lp.y == targetY) return;
        lp.x = targetX; lp.y = targetY;
        try { wm.updateViewLayout(view, lp); }
        catch (Throwable t) { DiagnosticLog.i(context, "FV_OP_HINT", "move failed=" + t); }
    }

'''
    if marker not in s: raise SystemExit('Operation hide marker missing')
    s = s.replace(marker, method + marker, 1)
if 'view.removeCallbacks(applyMove)' not in s:
    s = s.replace('        visible = false;\n        view.setVisibility(View.INVISIBLE);\n        lp.x = -sizePx;\n',
                  '        visible = false;\n        framePosted = false;\n        try { view.removeCallbacks(applyMove); } catch (Throwable ignored) {}\n        view.setVisibility(View.INVISIBLE);\n        lp.x = -sizePx;\n', 1)
    s = s.replace('    public void close() {\n        visible = false;\n',
                  '    public void close() {\n        visible = false;\n        framePosted = false;\n        try { view.removeCallbacks(applyMove); } catch (Throwable ignored) {}\n', 1)
p.write_text(s)

# LensAccessibilityService: throttle window inspection and publish only real state changes.
p = base / 'LensAccessibilityService.java'
s = p.read_text()
if 'import android.os.SystemClock;' not in s:
    s = s.replace('import android.hardware.HardwareBuffer;\n',
                  'import android.hardware.HardwareBuffer;\nimport android.os.SystemClock;\n')
if 'ENV_INSPECT_MIN_MS' not in s:
    s = s.replace('    private static volatile LensAccessibilityService s;\n',
                  '    private static volatile LensAccessibilityService s;\n    private static final long ENV_INSPECT_MIN_MS = 180L;\n')
    s = s.replace('    private volatile EnvironmentState env = new EnvironmentState("", false, 0, true, false, false);\n',
                  '    private volatile EnvironmentState env = new EnvironmentState("", false, 0, true, false, false);\n    private long lastEnvironmentInspectAt;\n')
old = '''    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            String top = env.topPackage();
            if (e != null && e.getPackageName() != null) {
                String pkg=e.getPackageName().toString();
                if (!pkg.equals(getPackageName()) && !pkg.equals("com.android.systemui")) top=pkg;
            }
            env = inspect(top);
            publishEnvironment();
        } catch (Throwable t) {
            DiagnosticLog.i(this,"ACCESSIBILITY","event failed="+t);
        }
    }
'''
new = '''    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            String oldTop = env.topPackage();
            String top = oldTop;
            if (e != null && e.getPackageName() != null) {
                String pkg=e.getPackageName().toString();
                if (!pkg.equals(getPackageName()) && !pkg.equals("com.android.systemui")) top=pkg;
            }
            boolean topChanged = !top.equals(oldTop);
            long now = SystemClock.uptimeMillis();
            if (!topChanged && now - lastEnvironmentInspectAt < ENV_INSPECT_MIN_MS) return;
            lastEnvironmentInspectAt = now;
            EnvironmentState next = inspect(top);
            if (sameEnvironment(env, next)) return;
            env = next;
            publishEnvironment();
        } catch (Throwable t) {
            DiagnosticLog.i(this,"ACCESSIBILITY","event failed="+t);
        }
    }

    private boolean sameEnvironment(EnvironmentState a, EnvironmentState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.topPackage().equals(b.topPackage())
                && a.imeVisible() == b.imeVisible()
                && a.imeTopPx() == b.imeTopPx()
                && a.statusBarVisible() == b.statusBarVisible()
                && a.notificationExpanded() == b.notificationExpanded()
                && a.fullscreen() == b.fullscreen();
    }
'''
if old in s: s = s.replace(old, new, 1)
elif 'sameEnvironment(EnvironmentState a' not in s: raise SystemExit('Accessibility event marker missing')
p.write_text(s)

# DiagnosticLog: move all disk writes off the UI/touch thread while preserving order.
p = base / 'DiagnosticLog.java'
p.write_text('''package com.yagay.floatlens;\n\nimport android.content.Context;\nimport android.os.Build;\nimport android.os.SystemClock;\nimport java.io.*;\nimport java.text.SimpleDateFormat;\nimport java.util.Date;\nimport java.util.HashMap;\nimport java.util.Locale;\nimport java.util.Map;\nimport java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;\nimport java.util.concurrent.Future;\nimport java.util.concurrent.TimeUnit;\n\npublic final class DiagnosticLog {\n    private static final Object LOCK = new Object();\n    private static final String FILE = "floatlens-fv-diagnostic.log";\n    private static final long MAX_BYTES = 2L * 1024L * 1024L;\n    private static final long HOT_LOG_INTERVAL_MS = 90L;\n    private static final Map<String, Long> HOT_LAST = new HashMap<>();\n    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {\n        Thread t = new Thread(r, "FloatLens-Diagnostic");\n        t.setPriority(Thread.NORM_PRIORITY - 1);\n        return t;\n    });\n    private static Context app;\n\n    public static void init(Context c) { if (c != null) app = c.getApplicationContext(); }\n    public static boolean enabled(Context c) {\n        Context x = c != null ? c.getApplicationContext() : app;\n        return x != null && x.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE)\n                .getBoolean(FloatSettings.K_DIAGNOSTIC, false);\n    }\n    public static void i(Context c, String tag, String msg) {\n        Context x = c != null ? c.getApplicationContext() : app;\n        if (x == null || !enabled(x)) return;\n        init(x);\n        long now = SystemClock.uptimeMillis();\n        String hotKey = hotKey(tag, msg);\n        if (hotKey != null) {\n            synchronized (HOT_LAST) {\n                long last = HOT_LAST.getOrDefault(hotKey, 0L);\n                if (now - last < HOT_LOG_INTERVAL_MS) return;\n                HOT_LAST.put(hotKey, now);\n            }\n        }\n        final Context target=x; final String finalTag=tag==null?"":tag; final String finalMsg=msg==null?"":msg;\n        try { IO.execute(() -> write(target, now, finalTag, finalMsg)); } catch (Throwable ignored) {}\n    }\n    private static void write(Context x,long now,String tag,String msg) {\n        synchronized (LOCK) {\n            try {\n                File f=file(x); if(f.length()>MAX_BYTES)rotate(f);\n                String ts=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());\n                String line=ts+" +"+now+"ms ["+tag+"] "+msg+"\\n";\n                try(FileOutputStream out=new FileOutputStream(f,true)){out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));}\n            } catch(Throwable ignored) {}\n        }\n    }\n    private static String hotKey(String tag,String msg) {\n        if(tag==null)return null;\n        if("TOUCH".equals(tag))return "TOUCH";\n        if("FV_PROBE".equals(tag)&&(msg==null||!msg.startsWith("BEGIN")))return "FV_PROBE";\n        if("FV_PROBE_VIEW".equals(tag)&&msg!=null&&msg.startsWith("MOVE"))return "FV_PROBE_VIEW_MOVE";\n        if("FV_OP_HINT".equals(tag)&&msg!=null&&msg.startsWith("mode="))return "FV_OP_HINT_MOVE";\n        if("FV_DIRECT".equals(tag)&&msg!=null&&msg.startsWith("REARM"))return "FV_DIRECT_REARM";\n        return null;\n    }\n    public static void sessionHeader(Context c) {\n        Context x=c!=null?c.getApplicationContext():app;if(x==null||!enabled(x))return;\n        i(x,"SESSION","FloatLens="+BuildConfig.VERSION_NAME+" sdk="+Build.VERSION.SDK_INT+" device="+Build.MANUFACTURER+"/"+Build.MODEL+" fingerprint="+Build.FINGERPRINT);\n    }\n    private static void flush(){try{Future<?> f=IO.submit(()->{});f.get(2,TimeUnit.SECONDS);}catch(Throwable ignored){}}\n    public static String read(Context c){Context x=c!=null?c.getApplicationContext():app;if(x==null)return "";flush();synchronized(LOCK){try{File f=file(x);if(!f.exists())return "";return java.nio.file.Files.readString(f.toPath());}catch(Throwable t){return "读取日志失败: "+t;}}}\n    public static void clear(Context c){Context x=c!=null?c.getApplicationContext():app;if(x==null)return;flush();synchronized(HOT_LAST){HOT_LAST.clear();}synchronized(LOCK){try{File f=file(x);if(f.exists())f.delete();}catch(Throwable ignored){}}}\n    private static File file(Context c){return new File(c.getFilesDir(),FILE);}\n    private static void rotate(File f)throws IOException{File old=new File(f.getParentFile(),FILE+".old");if(old.exists())old.delete();if(!f.renameTo(old)){try(FileOutputStream o=new FileOutputStream(f,false)){}}}\n    private DiagnosticLog(){}\n}\n''')
