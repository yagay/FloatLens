package com.yagay.floatlens;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.*;

import androidx.core.app.NotificationCompat;

import java.util.List;

/** Persistent floating icon service with FV-style temporary-follow positioning. */
public class FloatService extends Service implements android.content.SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String ACT_START = "com.yagay.floatlens.START";
    public static final String ACT_STOP = "com.yagay.floatlens.STOP";
    public static final String ACT_SHOW = "com.yagay.floatlens.SHOW";

    private static volatile FloatService instance;

    private WindowManager wm;
    private FlOverlayWindowHost iconHost;
    private FloatingIconLayoutPolicy layout;
    private FloatSettings fs;
    private FloatVisibilityController visibility;
    private CircleStateMachine circleState;
    private FloatIconView primary, secondary;
    private WindowManager.LayoutParams primaryLp, secondaryLp;
    private GestureTrailOverlay trail;
    private BroadcastReceiver screenReceiver;
    private EdgeWakeView wakeLeft, wakeRight;
    private WindowManager.LayoutParams wakeLeftLp, wakeRightLp;
    private boolean positionMoveArmed;
    private Integer imeRestoreY;
    private float lastActionX, lastActionY;

    public static FloatService get() { return instance; }

    @Override public void onCreate() {
        super.onCreate();
        DiagnosticLog.init(this);
        DiagnosticLog.sessionHeader(this);
        instance = this;
        fs = new FloatSettings(this);
        visibility = new FloatVisibilityController();
        circleState = new CircleStateMachine(this);
        fs.prefs().registerOnSharedPreferenceChangeListener(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        iconHost = new FlOverlayWindowHost(this);
        layout = new FloatingIconLayoutPolicy(this, fs);
        trail = new GestureTrailOverlay(this);
        createChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(27, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(27, notification);
        }
        registerScreenReceiver();
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && ACT_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACT_SHOW.equals(intent.getAction())) {
            visibility.setManualHidden(false);
        }
        show();
        return START_STICKY;
    }

    private void show() {
        if (!Settings.canDrawOverlays(this) && !LensAccessibilityService.ready()) return;
        if (primary == null) addPrimary();
        syncSecondary();
        recomputeVisibility();
    }

    private void addPrimary() {
        primaryLp = layout.createPrimary();
        primary = newIcon(primaryLp, false);
        primary.setAlpha(fs.alpha());
        addIconWindow(primary, primaryLp);
        layout.edgeHide(primaryLp);
        safeUpdate(primary, primaryLp);
    }

    private void syncSecondary() {
        if (fs.bothSide()) {
            if (secondary == null && primaryLp != null) {
                secondaryLp = layout.createMirror(primaryLp);
                secondary = newIcon(secondaryLp, true);
                secondary.setAlpha(fs.alpha());
                addIconWindow(secondary, secondaryLp);
                layout.edgeHide(secondaryLp);
                safeUpdate(secondary, secondaryLp);
            }
        } else if (secondary != null) {
            removeIconWindow(secondary);
            secondary = null;
            secondaryLp = null;
        }
    }

    private FloatIconView newIcon(WindowManager.LayoutParams lp, boolean mirrored) {
        final int[] origin = new int[2];
        final int[] mirrorOrigin = new int[2];
        final boolean[] originReady = {false};
        final boolean[] mirrorOriginReady = {false};
        final boolean[] directExpanded = {false};
        final int[] otherVisibility = {View.VISIBLE};

        return new FloatIconView(this, new FloatIconView.Callback() {
            @Override public void onDragStart() {
                origin[0] = lp.x;
                origin[1] = lp.y;
                originReady[0] = true;
                if (!mirrored && secondaryLp != null) {
                    mirrorOrigin[0] = secondaryLp.x;
                    mirrorOrigin[1] = secondaryLp.y;
                    mirrorOriginReady[0] = true;
                }
                DiagnosticLog.i(FloatService.this, "POSITION", "fl follow origin="
                        + origin[0] + "," + origin[1] + " moveMode=" + positionMoveArmed);
            }

            @Override public void onMove(int dxFromDown, int dyFromDown) {
                if (!originReady[0]) {
                    origin[0] = lp.x;
                    origin[1] = lp.y;
                    originReady[0] = true;
                }
                lp.x = origin[0] + dxFromDown;
                lp.y = origin[1] + dyFromDown;
                safeUpdate(mirrored ? secondary : primary, lp);
                if (!mirrored && secondary != null && secondaryLp != null) {
                    secondaryLp.y = (mirrorOriginReady[0] ? mirrorOrigin[1] : origin[1]) + dyFromDown;
                    safeUpdate(secondary, secondaryLp);
                }
            }

            @Override public void onRelease(boolean moved) {
                View icon = mirrored ? secondary : primary;
                if (positionMoveArmed && moved) {
                    if (fs.snap()) snap(lp, icon);
                    else {
                        layout.edgeHide(lp);
                        safeUpdate(icon, lp);
                    }
                    if (mirrored && primaryLp != null) {
                        primaryLp.y = lp.y;
                        layout.clamp(primaryLp, true);
                        layout.edgeHide(primaryLp);
                        safeUpdate(primary, primaryLp);
                    }
                    if (!mirrored && secondary != null && secondaryLp != null) syncMirrorPosition();
                    layout.persist(primaryLp);
                    positionMoveArmed = false;
                    originReady[0] = false;
                    mirrorOriginReady[0] = false;
                    DiagnosticLog.i(FloatService.this, "POSITION",
                            "committed explicit move x=" + lp.x + " y=" + lp.y);
                    android.widget.Toast.makeText(FloatService.this,
                            "移动图标位置已保存", android.widget.Toast.LENGTH_SHORT).show();
                    updateNotification();
                    return;
                }

                if (originReady[0]) {
                    lp.x = origin[0];
                    lp.y = origin[1];
                    layout.clamp(lp, true);
                    safeUpdate(icon, lp);
                } else {
                    layout.edgeHide(lp);
                    safeUpdate(icon, lp);
                }
                if (!mirrored && secondary != null && secondaryLp != null) {
                    if (mirrorOriginReady[0]) {
                        secondaryLp.x = mirrorOrigin[0];
                        secondaryLp.y = mirrorOrigin[1];
                        layout.clamp(secondaryLp, true);
                        safeUpdate(secondary, secondaryLp);
                    } else {
                        syncMirrorPosition();
                    }
                }
                DiagnosticLog.i(FloatService.this, "POSITION",
                        "restored temporary follow x=" + lp.x + " y=" + lp.y + " moved=" + moved);
                originReady[0] = false;
                mirrorOriginReady[0] = false;
            }

            @Override public void onGestureDecision(GestureDecision decision) {
                lastActionX = lp.x + lp.width / 2f;
                lastActionY = lp.y + lp.height / 2f;
                dispatchGesture(decision);
            }

            @Override public void onAction(String action) {
                lastActionX = lp.x + lp.width / 2f;
                lastActionY = lp.y + lp.height / 2f;
                DiagnosticLog.i(FloatService.this, "ACTION_LAYER", "direct action=" + action);
                ActionExecutor.execute(FloatService.this, action);
            }

            @Override public void onGestureStart(float x, float y) {
                if (fs.track()) trail.begin(x, y);
            }

            @Override public void onGestureMove(float x, float y) {
                if (fs.track()) trail.add(x, y);
            }

            @Override public void onGestureEnd(List<GesturePointSample> points) {
                trail.end();
            }

            @Override public void onDirectSelectionStart() {
                if (directExpanded[0]) return;
                View icon = mirrored ? secondary : primary;
                if (icon == null) return;
                directExpanded[0] = true;
                View other = mirrored ? primary : secondary;
                if (other != null) {
                    otherVisibility[0] = other.getVisibility();
                    other.setVisibility(View.INVISIBLE);
                }
                DiagnosticLog.i(FloatService.this, "FL_DIRECT", "keep compact touch owner window="
                        + lp.x + "," + lp.y + " " + lp.width + "x" + lp.height);
            }

            @Override public void onDirectSelectionEnd() {
                if (!directExpanded[0]) return;
                View other = mirrored ? primary : secondary;
                if (other != null) other.setVisibility(otherVisibility[0]);
                directExpanded[0] = false;
                DiagnosticLog.i(FloatService.this, "FL_DIRECT", "compact touch owner end window="
                        + lp.x + "," + lp.y + " " + lp.width + "x" + lp.height);
            }
        });
    }

    private void dispatchGesture(GestureDecision decision) {
        if (decision == null || decision.isNone()) return;
        if (decision.code() == GestureCode.ENTER_CIRCLE) circleState.enter();
        if (decision.code() == GestureCode.RECOGNIZE) circleState.recognizeStarted();
        String action = GestureActionMapper.actionFor(fs, decision);
        DiagnosticLog.i(this, "GESTURE_LAYER", "code=" + decision.code()
                + " label=" + GestureCode.label(decision.code())
                + " longTier=" + decision.longTier() + " -> action=" + action);
        if (!ActionId.NONE.equals(action)) ActionExecutor.execute(this, action);
    }

    public void armPositionMove() {
        positionMoveArmed = true;
        DiagnosticLog.i(this, "POSITION", "explicit move mode armed");
        android.widget.Toast.makeText(this,
                "移动图标位置：拖动悬浮球后松手保存", android.widget.Toast.LENGTH_SHORT).show();
        updateNotification();
    }

    public boolean isPositionMoveArmed() { return positionMoveArmed; }
    public void cancelPositionMove() { positionMoveArmed = false; updateNotification(); }

    public void onCircleCaptureStarted() { if (circleState != null) circleState.captureStarted(); }
    public void onCircleRecognizeStarted() { if (circleState != null) circleState.recognizeStarted(); }
    public void onOcrResults(int candidates) { if (circleState != null) circleState.resultsReady(candidates); }
    public void onCircleFinished(String reason) { if (circleState != null) circleState.finish(reason); }
    public CircleStateMachine.State circleState() {
        return circleState == null ? CircleStateMachine.State.IDLE : circleState.state();
    }

    private void syncMirrorPosition() {
        if (primaryLp == null || secondaryLp == null || secondary == null) return;
        layout.syncMirror(primaryLp, secondaryLp);
        safeUpdate(secondary, secondaryLp);
    }

    private void snap(WindowManager.LayoutParams lp, View view) {
        layout.snapToVisibleEdge(lp);
        safeUpdate(view, lp);
        layout.edgeHide(lp);
        safeUpdate(view, lp);
    }

    private boolean addIconWindow(View view, WindowManager.LayoutParams lp) {
        return iconHost != null && iconHost.add(view, lp, "float_icon");
    }

    private void removeIconWindow(View view) {
        if (view != null && iconHost != null) iconHost.remove(view, "float_icon");
    }

    public void onAccessibilityOverlayHostChanged(boolean available) {
        getMainExecutor().execute(() -> rehostIconWindows(available));
    }

    private void rehostIconWindows(boolean useAccessibility) {
        if (primary == null || primaryLp == null || iconHost == null) return;
        int target = useAccessibility
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (primaryLp.type == target && (secondaryLp == null || secondaryLp.type == target)) return;
        DiagnosticLog.i(this, "FL_WINDOW", "rehost icons target=" + target
                + " notif=" + visibility.notificationExpanded());
        rehostOne(primary, primaryLp, useAccessibility);
        if (secondary != null && secondaryLp != null) rehostOne(secondary, secondaryLp, useAccessibility);
    }

    private void rehostOne(View view, WindowManager.LayoutParams lp, boolean useAccessibility) {
        if (view != null && lp != null && iconHost != null) {
            iconHost.migrate(view, lp, useAccessibility, "float_icon_rehost");
        }
    }

    public void setManualHidden(boolean hidden) {
        visibility.setManualHidden(hidden);
        DiagnosticLog.i(this, "VISIBILITY", "manualHidden=" + hidden);
        recomputeVisibility();
    }

    public boolean isManualHidden() { return visibility.manualHidden(); }

    public void setScreenshotHidden(boolean hidden) {
        visibility.setScreenshotHidden(hidden);
        DiagnosticLog.i(this, "VISIBILITY", "screenshotHidden=" + hidden);
        recomputeVisibility();
    }

    public void setIconVisible(boolean visible) { setScreenshotHidden(!visible); }

    public void restoreConfiguredVisibility() {
        visibility.setScreenshotHidden(false);
        recomputeVisibility();
    }

    public void onAccessibilityEnvironment(EnvironmentState state) {
        getMainExecutor().execute(() -> {
            if (state == null) return;
            DiagnosticLog.i(this, "ENV", "pkg=" + state.topPackage()
                    + " ime=" + state.imeVisible() + " imeTop=" + state.imeTopPx()
                    + " notif=" + state.notificationExpanded()
                    + " statusBar=" + state.statusBarVisible()
                    + " fullscreen=" + state.fullscreen());
            boolean imeChanged = visibility.imeVisible() != state.imeVisible()
                    || visibility.imeTopPx() != state.imeTopPx();
            visibility.applyEnvironment(fs, state);
            if (imeChanged) updateImeAvoidance();
            recomputeVisibility();
        });
    }

    private void updateImeAvoidance() {
        if (primaryLp == null) return;
        if (!fs.imeAvoid()) {
            restoreImeY();
            return;
        }
        if (visibility.imeVisible() && visibility.imeTopPx() > 0) {
            if (imeRestoreY == null) imeRestoreY = primaryLp.y;
            int target = Math.max(0, visibility.imeTopPx() - primaryLp.height - dp(8));
            if (primaryLp.y > target) {
                primaryLp.y = target;
                safeUpdate(primary, primaryLp);
                if (secondaryLp != null) {
                    secondaryLp.y = target;
                    safeUpdate(secondary, secondaryLp);
                }
            }
        } else {
            restoreImeY();
        }
    }

    private void restoreImeY() {
        if (imeRestoreY == null || primaryLp == null) return;
        primaryLp.y = imeRestoreY;
        layout.clamp(primaryLp, true);
        safeUpdate(primary, primaryLp);
        if (secondaryLp != null) {
            secondaryLp.y = primaryLp.y;
            layout.clamp(secondaryLp, true);
            safeUpdate(secondary, secondaryLp);
        }
        imeRestoreY = null;
    }

    private void updateLockVisibility() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        visibility.setLockHidden(km != null && km.isKeyguardLocked() && !fs.showOnLock());
        recomputeVisibility();
    }

    private void recomputeVisibility() {
        boolean visible = visibility.visible();
        DiagnosticLog.i(this, "VISIBILITY", visibility.diagnostic());
        int state = visible ? View.VISIBLE : View.INVISIBLE;
        if (primary != null) primary.setVisibility(state);
        if (secondary != null) secondary.setVisibility(state);
        if (!visible) trail.end();
        syncWakeViews();
        updateNotification();
    }

    private void syncWakeViews() {
        if (visibility.wakeEdgesNeeded(fs)) addWakeViews();
        else removeWakeViews();
    }

    private void addWakeViews() {
        if (wakeLeft != null) return;
        int width = dp(18);
        wakeLeft = new EdgeWakeView(this, true, () -> setManualHidden(false));
        wakeRight = new EdgeWakeView(this, false, () -> setManualHidden(false));
        wakeLeftLp = wakeLp(width, Gravity.LEFT);
        wakeRightLp = wakeLp(width, Gravity.RIGHT);
        try {
            wm.addView(wakeLeft, wakeLeftLp);
            wm.addView(wakeRight, wakeRightLp);
        } catch (Throwable t) {
            removeWakeViews();
        }
    }

    private WindowManager.LayoutParams wakeLp(int width, int side) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | side;
        return lp;
    }

    private void removeWakeViews() {
        if (wakeLeft != null) try { wm.removeView(wakeLeft); } catch (Throwable ignored) { }
        if (wakeRight != null) try { wm.removeView(wakeRight); } catch (Throwable ignored) { }
        wakeLeft = wakeRight = null;
        wakeLeftLp = wakeRightLp = null;
    }

    public void clickScreenUnderIcon() {
        DiagnosticLog.i(this, "ACTION", "clickUnder x=" + Math.round(lastActionX)
                + " y=" + Math.round(lastActionY));
        LensAccessibilityService accessibility = LensAccessibilityService.get();
        if (accessibility == null) {
            android.widget.Toast.makeText(this,
                    "需要开启 FloatLens 无障碍服务", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        setScreenshotHidden(true);
        getMainExecutor().execute(() -> new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    boolean ok = accessibility.tap(lastActionX, lastActionY);
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> setScreenshotHidden(false), 100);
                    if (!ok) android.widget.Toast.makeText(this,
                            "点击下方屏幕失败", android.widget.Toast.LENGTH_SHORT).show();
                }, 80));
    }

    public void refreshAppearance() {
        fs = new FloatSettings(this);
        layout.updateSettings(fs);
        visibility.applySettings(fs);
        int px = layout.iconPx();
        if (primary != null && primaryLp != null) {
            primaryLp.width = primaryLp.height = px;
            primary.setAlpha(fs.alpha());
            primary.refreshSettings();
            layout.clamp(primaryLp, true);
            layout.edgeHide(primaryLp);
            safeUpdate(primary, primaryLp);
        }
        if (secondary != null && secondaryLp != null) {
            secondaryLp.width = secondaryLp.height = px;
            secondary.setAlpha(fs.alpha());
            secondary.refreshSettings();
            layout.clamp(secondaryLp, true);
            layout.edgeHide(secondaryLp);
            safeUpdate(secondary, secondaryLp);
        }
        syncSecondary();
        updateLockVisibility();
        updateImeAvoidance();
        recomputeVisibility();
    }

    @Override public void onSharedPreferenceChanged(android.content.SharedPreferences prefs, String key) {
        refreshAppearance();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        layout.persist(primaryLp);
        super.onConfigurationChanged(configuration);
        imeRestoreY = null;
        removeIcons();
        fs = new FloatSettings(this);
        layout.updateSettings(fs);
        visibility.applySettings(fs);
        show();
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) { updateLockVisibility(); }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    private void removeIcons() {
        trail.end();
        removeWakeViews();
        if (primary != null) removeIconWindow(primary);
        if (secondary != null) removeIconWindow(secondary);
        primary = secondary = null;
        primaryLp = secondaryLp = null;
    }

    private void safeUpdate(View view, WindowManager.LayoutParams lp) {
        if (view != null && lp != null && iconHost != null) {
            iconHost.update(view, lp, "float_icon_update");
        }
    }

    @Override public void onDestroy() {
        layout.persist(primaryLp);
        if (circleState != null) circleState.finish("service_destroy");
        removeIcons();
        try { fs.prefs().unregisterOnSharedPreferenceChangeListener(this); } catch (Throwable ignored) { }
        if (screenReceiver != null) try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) { }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        Intent stop = new Intent(this, FloatService.class).setAction(ACT_STOP);
        Intent show = new Intent(this, FloatService.class).setAction(ACT_SHOW);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent showPi = PendingIntent.getService(this, 3, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String state = positionMoveArmed ? "移动图标位置：拖动后松手保存"
                : visibility.manualHidden() ? "图标已手动隐藏"
                : visibility.lockHidden() ? "锁屏隐藏"
                : visibility.fullscreenHidden() ? "全屏应用隐藏"
                : visibility.appHidden() ? "当前应用按规则隐藏"
                : circleState != null && circleState.active() ? "Circle: " + circleState.state()
                : visibility.notificationExpanded() ? "通知栏已展开"
                : "点击进入设置";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "floatlens")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("FloatLens 悬浮图标已运行")
                .setContentText(state)
                .setContentIntent(openPi)
                .setOngoing(true);
        if (visibility.manualHidden()) builder.addAction(0, "显示图标", showPi);
        builder.addAction(0, "停止", stopPi);
        return builder.build();
    }

    private void updateNotification() {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(27, buildNotification());
        } catch (Throwable ignored) { }
    }

    private void createChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(
                "floatlens", "FloatLens 悬浮服务", NotificationManager.IMPORTANCE_LOW));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
