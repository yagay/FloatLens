from pathlib import Path
import re

ROOT = Path('app/src/main/java/com/yagay/floatlens')

def read(name):
    return (ROOT / name).read_text(encoding='utf-8')

def write(name, text):
    (ROOT / name).write_text(text, encoding='utf-8')

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f'missing pattern: {label}')
    return text.replace(old, new, 1)

# 1) Keep Accessibility traversal off MotionEvent MOVE by warming a cache from accessibility events.
s = read('LensAccessibilityService.java')
s = replace_once(s,
    '        s=this;\n        try {',
    '        s=this;\n        AccessibilityCandidateCache.requestRefresh(this, "service_connected", 0L);\n        try {',
    'service connect cache')
s = replace_once(s,
    '    @Override public void onDestroy(){\n        FloatService f=FloatService.get();',
    '    @Override public void onDestroy(){\n        AccessibilityCandidateCache.clear();\n        FloatService f=FloatService.get();',
    'service destroy cache')
s = replace_once(s,
    '    @Override public void onAccessibilityEvent(AccessibilityEvent e) {\n        try {',
    '    @Override public void onAccessibilityEvent(AccessibilityEvent e) {\n        AccessibilityCandidateCache.requestRefresh(this,\n                "event_" + (e == null ? 0 : e.getEventType()));\n        try {',
    'service event cache')
write('LensAccessibilityService.java', s)

# 2) ViewHoverOverlay becomes a prepared-geometry consumer. MOVE does zero Accessibility traversal.
s = read('ViewHoverOverlay.java')
s = s.replace('import android.os.SystemClock;\n', '')
s = s.replace('    private static final long POINT_REFRESH_MIN_MS = 45L;\n    private static final float POINT_REFRESH_DISTANCE_DP = 4f;\n', '')
s = s.replace('    private final float pointRefreshDistancePx;\n', '')
s = s.replace('    private long lastPointRefreshAt;\n    private float lastPointX = Float.NaN;\n    private float lastPointY = Float.NaN;\n',
              '    private long loadedCacheVersion = Long.MIN_VALUE;\n')
s = s.replace('        pointRefreshDistancePx = POINT_REFRESH_DISTANCE_DP\n                * context.getResources().getDisplayMetrics().density;\n', '')
s = replace_once(s,
    '    public void beginAt(float selectionX, float selectionY) {\n        refreshAccessibilityAtPoint(selectionX, selectionY, true);\n        applySelection(selectionX, selectionY);\n    }',
    '    public void beginAt(float selectionX, float selectionY) {\n        reloadPreparedCache(true);\n        applySelection(selectionX, selectionY);\n    }',
    'beginAt')
pattern = re.compile(r'''    /\*\*\n     \* Refresh only the Accessibility chain under the probe\..*?\n    }\n\n    /\*\* MOVE-time behavior: point refresh followed by prepared contains\(x,y\) selection\. \*/''', re.S)
replacement = '''    /** Load the latest event-prepared immutable geometry snapshot. */
    private void reloadPreparedCache(boolean force) {
        if (accessibility == null) return;
        long nextVersion = AccessibilityCandidateCache.version();
        if (!force && nextVersion == loadedCacheVersion) return;

        List<ScreenCandidate> cached = AccessibilityCandidateCache.snapshot();
        model.setAccessibility(cached == null ? Collections.emptyList() : cached);
        loadedCacheVersion = nextVersion;
        DiagnosticLog.i(context, "FL_TREE_CACHE", "SNAPSHOT_APPLY version=" + nextVersion
                + " total=" + model.size() + " force=" + force);

        if (model.isEmpty()) {
            AccessibilityCandidateCache.requestRefresh(accessibility, "direct_empty_cache", 0L);
        }
    }

    /** MOVE-time behavior: cheap prepared contains(x,y) selection only. */'''
s, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit(f'point refresh method replacement count={n}')
s = replace_once(s,
    '    public void update(float selectionX, float selectionY) {\n        if (accessibility == null) return;\n        refreshAccessibilityAtPoint(selectionX, selectionY, false);\n        applySelection(selectionX, selectionY);\n    }',
    '    public void update(float selectionX, float selectionY) {\n        if (accessibility == null) return;\n        reloadPreparedCache(false);\n        applySelection(selectionX, selectionY);\n    }',
    'overlay update')
s = s.replace('        lastPointRefreshAt = 0L;\n        lastPointX = lastPointY = Float.NaN;\n',
              '        loadedCacheVersion = Long.MIN_VALUE;\n')
write('ViewHoverOverlay.java', s)

# 3) FV q.run/q0 behavior: after 400ms stable dwell, the same FloatIconView becomes the fullscreen
# touch owner. Do not keep moving the actual WM window after takeover; retain only virtual compact
# coordinates for probe geometry.
s = read('FloatIconView.java')
s = replace_once(s,
    'import android.view.View;\n',
    'import android.view.View;\nimport android.view.WindowManager;\n',
    'WindowManager import')
s = replace_once(s,
    '    private int fvWindowX, fvWindowY;\n',
    '    private int fvWindowX, fvWindowY;\n\n    private boolean fvOwnerFullscreen;\n    private int fvOwnerSavedX, fvOwnerSavedY, fvOwnerSavedWidth, fvOwnerSavedHeight;\n',
    'fullscreen fields')
s = replace_once(s,
    '            directSelectionActive = true;\n            cb.onGestureEnd(session.snapshot());\n            cb.onDirectSelectionStart();\n            boolean ok = selectionEngine.activateDirect(lastSelectionRawX, lastSelectionRawY);\n            if (!ok) {\n                directSelectionActive = false;\n                cb.onDirectSelectionEnd();',
    '            directSelectionActive = true;\n            cb.onGestureEnd(session.snapshot());\n            enterFvFullscreenOwner();\n            cb.onDirectSelectionStart();\n            boolean ok = selectionEngine.activateDirect(lastSelectionRawX, lastSelectionRawY);\n            if (!ok) {\n                exitFvFullscreenOwner();\n                directSelectionActive = false;\n                cb.onDirectSelectionEnd();',
    'direct entry fullscreen')
s = replace_once(s,
    '        cancelDirectSelectionTimer();\n        if(selectionEngine!=null)selectionEngine.cancel();',
    '        cancelDirectSelectionTimer();\n        exitFvFullscreenOwner();\n        if(selectionEngine!=null)selectionEngine.cancel();',
    'detach fullscreen cleanup')
s = replace_once(s,
    '            if(directSelectionActive){if(selectionEngine!=null)selectionEngine.cancel();cb.onDirectSelectionEnd();directSelectionActive=false;}',
    '            if(directSelectionActive){if(selectionEngine!=null)selectionEngine.cancel();exitFvFullscreenOwner();cb.onDirectSelectionEnd();directSelectionActive=false;}',
    'pointer down cleanup')
s = replace_once(s,
    '''                if(directSelectionActive){
                    if ((moveDx!=0||moveDy!=0) && followStarted) {
                        updateFvWindowTracking(moveDx, moveDy);
                        cb.onMove(moveDx, moveDy);
                        session.moved=true;
                    }
                    if(selectionEngine!=null)selectionEngine.updateDirect(rx,ry);
                    return true;
                }''',
    '''                if(directSelectionActive){
                    // FV q0 has already expanded the same owner to fullscreen. Keep only the
                    // virtual compact-icon trajectory; moving the actual WM window here would
                    // destroy the fullscreen touch owner.
                    if ((moveDx!=0||moveDy!=0) && followStarted) {
                        updateFvWindowTracking(moveDx, moveDy);
                        session.moved=true;
                    }
                    if(selectionEngine!=null)selectionEngine.updateDirect(rx,ry);
                    return true;
                }''',
    'direct move')
s = replace_once(s,
    '                    selectionTookOver=selectionEngine!=null&&selectionEngine.finishDirect(rx,ry);\n                    cb.onDirectSelectionEnd();',
    '                    selectionTookOver=selectionEngine!=null&&selectionEngine.finishDirect(rx,ry);\n                    exitFvFullscreenOwner();\n                    cb.onDirectSelectionEnd();',
    'direct up fullscreen restore')
s = replace_once(s,
    '                    if(selectionEngine!=null)selectionEngine.cancel();\n                    cb.onDirectSelectionEnd();\n                    directSelectionActive=false;',
    '                    if(selectionEngine!=null)selectionEngine.cancel();\n                    exitFvFullscreenOwner();\n                    cb.onDirectSelectionEnd();\n                    directSelectionActive=false;',
    'cancel fullscreen restore')

insert_before = '    private void finish(long now, boolean cancelled) {'
if insert_before not in s:
    raise SystemExit('missing insertion point for fullscreen methods')
fullscreen_methods = '''    /** FV FloatIconView.q0(): turn the existing touch owner into a fullscreen transparent window. */
    private void enterFvFullscreenOwner() {
        if (fvOwnerFullscreen) return;
        if (!(getLayoutParams() instanceof WindowManager.LayoutParams lp)) {
            DiagnosticLog.i(getContext(), "FL_DIRECT", "FULLSCREEN_OWNER unavailable layoutParams");
            return;
        }

        fvOwnerSavedX = lp.x;
        fvOwnerSavedY = lp.y;
        fvOwnerSavedWidth = lp.width;
        fvOwnerSavedHeight = lp.height;

        lp.x = 0;
        lp.y = 0;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        if (updateOwnerWindow(lp)) {
            fvOwnerFullscreen = true;
            DiagnosticLog.i(getContext(), "FL_DIRECT", "FULLSCREEN_OWNER ENTER from="
                    + fvOwnerSavedX + "," + fvOwnerSavedY + " "
                    + fvOwnerSavedWidth + "x" + fvOwnerSavedHeight
                    + " now=" + getWidth() + "x" + getHeight());
        } else {
            lp.x = fvOwnerSavedX;
            lp.y = fvOwnerSavedY;
            lp.width = fvOwnerSavedWidth;
            lp.height = fvOwnerSavedHeight;
        }
    }

    private void exitFvFullscreenOwner() {
        if (!fvOwnerFullscreen) return;
        if (!(getLayoutParams() instanceof WindowManager.LayoutParams lp)) {
            fvOwnerFullscreen = false;
            return;
        }
        lp.x = fvOwnerSavedX;
        lp.y = fvOwnerSavedY;
        lp.width = fvOwnerSavedWidth;
        lp.height = fvOwnerSavedHeight;
        updateOwnerWindow(lp);
        fvOwnerFullscreen = false;
        DiagnosticLog.i(getContext(), "FL_DIRECT", "FULLSCREEN_OWNER EXIT restore="
                + lp.x + "," + lp.y + " " + lp.width + "x" + lp.height);
    }

    private boolean updateOwnerWindow(WindowManager.LayoutParams lp) {
        LensAccessibilityService accessibility = LensAccessibilityService.get();
        if (accessibility != null && accessibility.updateAccessibilityOverlay(this, lp)) return true;
        try {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(this, lp);
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(getContext(), "FL_DIRECT", "FULLSCREEN_OWNER update failed=" + t);
            return false;
        }
    }

'''
s = s.replace(insert_before, fullscreen_methods + insert_before, 1)
s = replace_once(s,
    '    private void resetSession(){\n        cancelLongPress();',
    '    private void resetSession(){\n        exitFvFullscreenOwner();\n        cancelLongPress();',
    'reset fullscreen cleanup')
write('FloatIconView.java', s)

# 4) Keep one region workspace attached for the whole direct-selection session, like FV m2.g.
s = read('FlRegionFrameOverlay.java')
s = replace_once(s,
    '    void show(Rect screenRect) {',
    '    void prepare() {\n        ensureAttached();\n        frame.clear();\n    }\n\n    void hide() {\n        frame.clear();\n    }\n\n    void show(Rect screenRect) {',
    'region prepare/hide')
write('FlRegionFrameOverlay.java', s)

s = read('ViewSelectionEngine.java')
s = replace_once(s,
    '        closeDirectRegionFrame();\n        resetViewReadiness();',
    '        closeDirectRegionFrame();\n        directRegionFrame = new FlRegionFrameOverlay(context);\n        directRegionFrame.prepare();\n        resetViewReadiness();',
    'persistent region workspace')
s = replace_once(s,
    '''        if (state != State.DIRECT || !shouldUseRegionNow()) {
            closeDirectRegionFrame();
            return;
        }''',
    '''        if (state != State.DIRECT || !shouldUseRegionNow()) {
            if (directRegionFrame != null) directRegionFrame.hide();
            return;
        }''',
    'region visual hide')
s = s.replace('    /** Create the View layer once and let it refresh only the candidate chain at the current point. */',
              '    /** Create the View layer once; MOVE reads only the prepared Accessibility geometry cache. */')
write('ViewSelectionEngine.java', s)

print('FV architecture patch applied successfully')
