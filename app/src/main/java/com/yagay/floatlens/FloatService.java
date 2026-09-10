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
    public static final String ACT_START="com.yagay.floatlens.START", ACT_STOP="com.yagay.floatlens.STOP", ACT_SHOW="com.yagay.floatlens.SHOW";
    private static volatile FloatService instance;
    private WindowManager wm; private FloatSettings fs; private CircleStateMachine circleState;
    private FloatIconView primary,secondary; private WindowManager.LayoutParams primaryLp,secondaryLp;
    private GestureTrailOverlay trail; private BroadcastReceiver screenReceiver;
    private EdgeWakeView wakeLeft,wakeRight; private WindowManager.LayoutParams wakeLeftLp,wakeRightLp;
    private boolean manualHidden,screenshotHidden,appHidden,lockHidden,fullscreenHidden;
    private boolean imeVisible,notificationExpanded,statusBarVisible=true;
    private boolean positionMoveArmed;
    private String topPackage=""; private int imeTopPx; private Integer imeRestoreY;
    private float lastActionX,lastActionY;
    public static FloatService get(){return instance;}

    @Override public void onCreate(){super.onCreate();
        DiagnosticLog.init(this);
        DiagnosticLog.sessionHeader(this);instance=this;fs=new FloatSettings(this);circleState=new CircleStateMachine(this);fs.prefs().registerOnSharedPreferenceChangeListener(this);wm=(WindowManager)getSystemService(WINDOW_SERVICE);trail=new GestureTrailOverlay(this);createChannel();Notification n=buildNotification();if(Build.VERSION.SDK_INT>=34)startForeground(27,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(27,n);registerScreenReceiver();}
    @Override public int onStartCommand(Intent i,int flags,int id){if(i!=null&&ACT_STOP.equals(i.getAction())){stopSelf();return START_NOT_STICKY;}if(i!=null&&ACT_SHOW.equals(i.getAction()))manualHidden=false;show();return START_STICKY;}
    private void show(){if(!Settings.canDrawOverlays(this))return;if(primary==null)addPrimary();syncSecondary();recomputeVisibility();}

    private void addPrimary(){int px=iconPx();primaryLp=makeLp(px);int[] wh=displaySize();int defaultX=wh[0]-px,defaultY=wh[1]/3;primaryLp.x=fs.prefs().getInt(fs.posXKey(),fs.prefs().getInt(FloatSettings.K_POS_X,defaultX));primaryLp.y=fs.prefs().getInt(fs.posYKey(),fs.prefs().getInt(FloatSettings.K_POS_Y,defaultY));int side=fs.savedSide(primaryLp.x+px/2<wh[0]/2?0:1);primaryLp.x=side==0?0:wh[0]-px;clamp(primaryLp,false);primary=newIcon(primaryLp,false);primary.setAlpha(fs.alpha());wm.addView(primary,primaryLp);edgeHide(primaryLp);safeUpdate(primary,primaryLp);}
    private void syncSecondary(){if(fs.bothSide()){if(secondary==null&&primaryLp!=null){secondaryLp=makeLp(iconPx());int[] wh=displaySize();secondaryLp.x=isLeft(primaryLp,wh[0])?wh[0]-secondaryLp.width:0;secondaryLp.y=primaryLp.y;secondary=newIcon(secondaryLp,true);secondary.setAlpha(fs.alpha());wm.addView(secondary,secondaryLp);edgeHide(secondaryLp);safeUpdate(secondary,secondaryLp);}}else if(secondary!=null){try{wm.removeView(secondary);}catch(Throwable ignored){}secondary=null;secondaryLp=null;}}

    private FloatIconView newIcon(WindowManager.LayoutParams lp,boolean mirrored){
        final int[] origin=new int[2];
        final int[] mirrorOrigin=new int[2];
        final boolean[] originReady={false};
        final boolean[] mirrorOriginReady={false};
        return new FloatIconView(this,new FloatIconView.Callback(){
            @Override public void onDragStart(){
                // FV Full Capture shows c0() moving all over the screen during a gesture, while
                // every following touch starts again from the same resting coordinate. Keep that
                // resting coordinate before exposing the icon for the temporary follow animation.
                origin[0]=lp.x; origin[1]=lp.y; originReady[0]=true;
                if(!mirrored&&secondaryLp!=null){mirrorOrigin[0]=secondaryLp.x;mirrorOrigin[1]=secondaryLp.y;mirrorOriginReady[0]=true;}
                restoreFully(lp);safeUpdate(mirrored?secondary:primary,lp);
                DiagnosticLog.i(FloatService.this,"POSITION","temporary follow origin="+origin[0]+","+origin[1]+" moveMode="+positionMoveArmed);
            }
            @Override public void onMove(int dx,int dy){
                lp.x+=dx;lp.y+=dy;clamp(lp,false);safeUpdate(mirrored?secondary:primary,lp);
                if(!mirrored&&secondary!=null&&secondaryLp!=null){secondaryLp.y=lp.y;clamp(secondaryLp,false);safeUpdate(secondary,secondaryLp);}
            }
            @Override public void onRelease(boolean moved){
                View icon=mirrored?secondary:primary;
                if(positionMoveArmed&&moved){
                    // Only the explicit FV-style "move icon position" mode commits a new resting
                    // position. Normal gestures, View selection and Circle never reach this branch.
                    if(fs.snap())snap(lp,icon);else{edgeHide(lp);safeUpdate(icon,lp);}
                    if(mirrored&&primaryLp!=null){primaryLp.y=lp.y;clamp(primaryLp,true);edgeHide(primaryLp);safeUpdate(primary,primaryLp);}
                    if(!mirrored&&secondary!=null&&secondaryLp!=null)syncMirrorPosition();
                    persistPosition();
                    positionMoveArmed=false;
                    originReady[0]=false;mirrorOriginReady[0]=false;
                    DiagnosticLog.i(FloatService.this,"POSITION","committed explicit move x="+lp.x+" y="+lp.y);
                    android.widget.Toast.makeText(FloatService.this,"图标位置已保存",android.widget.Toast.LENGTH_SHORT).show();
                    updateNotification();
                    return;
                }

                // Ordinary FV gesture: restore the exact resting position captured on DOWN. Do not
                // snap the temporary end point and do not persist it.
                if(originReady[0]){lp.x=origin[0];lp.y=origin[1];clamp(lp,true);safeUpdate(icon,lp);}
                else{edgeHide(lp);safeUpdate(icon,lp);}
                if(!mirrored&&secondary!=null&&secondaryLp!=null){
                    if(mirrorOriginReady[0]){secondaryLp.x=mirrorOrigin[0];secondaryLp.y=mirrorOrigin[1];clamp(secondaryLp,true);safeUpdate(secondary,secondaryLp);}
                    else syncMirrorPosition();
                }
                DiagnosticLog.i(FloatService.this,"POSITION","restored temporary follow x="+lp.x+" y="+lp.y+" moved="+moved);
                originReady[0]=false;mirrorOriginReady[0]=false;
            }
            @Override public void onGestureDecision(GestureDecision d){lastActionX=lp.x+lp.width/2f;lastActionY=lp.y+lp.height/2f;dispatchGesture(d);}
            @Override public void onAction(String a){lastActionX=lp.x+lp.width/2f;lastActionY=lp.y+lp.height/2f;DiagnosticLog.i(FloatService.this,"ACTION_LAYER","direct action="+a);ActionExecutor.execute(FloatService.this,a);}
            @Override public void onGestureStart(float x,float y){if(fs.track())trail.begin(x,y);}
            @Override public void onGestureMove(float x,float y){if(fs.track())trail.add(x,y);}
            @Override public void onGestureEnd(List<GesturePointSample> points){trail.end();}
        });
    }

    private void dispatchGesture(GestureDecision d){
        if(d==null||d.isNone())return;
        if(d.code()==GestureCode.ENTER_CIRCLE)circleState.enter();
        if(d.code()==GestureCode.RECOGNIZE)circleState.recognizeStarted();
        String action=GestureActionMapper.actionFor(fs,d);
        DiagnosticLog.i(this,"GESTURE_LAYER","code="+d.code()+" label="+GestureCode.label(d.code())+" longTier="+d.longTier()+" -> action="+action);
        if(!ActionId.NONE.equals(action))ActionExecutor.execute(this,action);
    }

    /** Arms the one-shot FV-style position edit. Normal icon gestures never change the rest position. */
    public void armPositionMove(){
        positionMoveArmed=true;
        DiagnosticLog.i(this,"POSITION","explicit move mode armed");
        android.widget.Toast.makeText(this,"移动图标位置：拖动悬浮球后松手保存",android.widget.Toast.LENGTH_SHORT).show();
        updateNotification();
    }
    public boolean isPositionMoveArmed(){return positionMoveArmed;}
    public void cancelPositionMove(){positionMoveArmed=false;updateNotification();}

    public void onCircleCaptureStarted(){if(circleState!=null)circleState.captureStarted();}
    public void onCircleRecognizeStarted(){if(circleState!=null)circleState.recognizeStarted();}
    public void onOcrResults(int candidates){if(circleState!=null)circleState.resultsReady(candidates);}
    public void onCircleFinished(String reason){if(circleState!=null)circleState.finish(reason);}
    public CircleStateMachine.State circleState(){return circleState==null?CircleStateMachine.State.IDLE:circleState.state();}

    private void syncMirrorPosition(){if(primaryLp==null||secondaryLp==null||secondary==null)return;int[] wh=displaySize();secondaryLp.x=isLeft(primaryLp,wh[0])?wh[0]-secondaryLp.width:0;secondaryLp.y=primaryLp.y;edgeHide(secondaryLp);safeUpdate(secondary,secondaryLp);}
    private WindowManager.LayoutParams makeLp(int px){WindowManager.LayoutParams lp=new WindowManager.LayoutParams(px,px,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);lp.gravity=Gravity.TOP|Gravity.START;return lp;}
    private int iconPx(){return Math.round(fs.sizeDp()*getResources().getDisplayMetrics().density);} private int[] displaySize(){android.graphics.Rect b=wm.getCurrentWindowMetrics().getBounds();return new int[]{b.width(),b.height()};}
    private boolean isLeft(WindowManager.LayoutParams lp,int sw){return lp.x+lp.width/2<sw/2;}
    private void clamp(WindowManager.LayoutParams lp,boolean allowHidden){int[] wh=displaySize();int hidden=allowHidden?Math.round(lp.width*fs.hiddenPercent()/100f):0;lp.x=Math.max(-hidden,Math.min(lp.x,wh[0]-lp.width+hidden));lp.y=Math.max(0,Math.min(lp.y,wh[1]-lp.height));}
    private void snap(WindowManager.LayoutParams lp,View v){int[] wh=displaySize();lp.x=isLeft(lp,wh[0])?0:wh[0]-lp.width;clamp(lp,false);safeUpdate(v,lp);edgeHide(lp);safeUpdate(v,lp);}
    private void edgeHide(WindowManager.LayoutParams lp){int hp=fs.hiddenPercent();if(hp<=0)return;int[] wh=displaySize();boolean left=isLeft(lp,wh[0]);int hidden=Math.round(lp.width*hp/100f);lp.x=left?-hidden:wh[0]-lp.width+hidden;clamp(lp,true);DiagnosticLog.i(this,"EDGE","side="+(left?"L":"R")+" visiblePct="+fs.showPercentage()+" hiddenPx="+hidden+" x="+lp.x);}
    private void restoreFully(WindowManager.LayoutParams lp){int[] wh=displaySize();lp.x=isLeft(lp,wh[0])?0:wh[0]-lp.width;clamp(lp,false);}
    private void persistPosition(){if(primaryLp==null)return;DiagnosticLog.i(this,"POSITION","persist x="+primaryLp.x+" y="+primaryLp.y+" side="+(isLeft(primaryLp,displaySize()[0])?"L":"R")+" landscape="+fs.isLandscape());int[] wh=displaySize();fs.saveSide(isLeft(primaryLp,wh[0]));fs.prefs().edit().putInt(fs.posXKey(),primaryLp.x).putInt(fs.posYKey(),primaryLp.y).apply();}

    public void setManualHidden(boolean h){manualHidden=h;DiagnosticLog.i(this,"VISIBILITY","manualHidden="+h);recomputeVisibility();} public boolean isManualHidden(){return manualHidden;}
    public void setScreenshotHidden(boolean h){screenshotHidden=h;DiagnosticLog.i(this,"VISIBILITY","screenshotHidden="+h);recomputeVisibility();} public void setIconVisible(boolean v){setScreenshotHidden(!v);} public void restoreConfiguredVisibility(){screenshotHidden=false;recomputeVisibility();}

    public void onAccessibilityEnvironment(EnvironmentState e){getMainExecutor().execute(()->{if(e==null)return;DiagnosticLog.i(this,"ENV","pkg="+e.topPackage()+" ime="+e.imeVisible()+" imeTop="+e.imeTopPx()+" notif="+e.notificationExpanded()+" statusBar="+e.statusBarVisible()+" fullscreen="+e.fullscreen());topPackage=e.topPackage();appHidden=fs.shouldHideForPackage(topPackage);notificationExpanded=e.notificationExpanded();statusBarVisible=e.statusBarVisible();fullscreenHidden=fs.hideWhenFullscreen()&&e.fullscreen();boolean imeChanged=imeVisible!=e.imeVisible()||imeTopPx!=e.imeTopPx();imeVisible=e.imeVisible();imeTopPx=e.imeTopPx();if(imeChanged)updateImeAvoidance();recomputeVisibility();});}

    private void updateImeAvoidance(){if(primaryLp==null)return;if(!fs.imeAvoid()){restoreImeY();return;}if(imeVisible&&imeTopPx>0){if(imeRestoreY==null)imeRestoreY=primaryLp.y;int target=Math.max(0,imeTopPx-primaryLp.height-dp(8));if(primaryLp.y>target){primaryLp.y=target;safeUpdate(primary,primaryLp);if(secondaryLp!=null){secondaryLp.y=target;safeUpdate(secondary,secondaryLp);}}}else restoreImeY();}
    private void restoreImeY(){if(imeRestoreY==null||primaryLp==null)return;primaryLp.y=imeRestoreY;clamp(primaryLp,true);safeUpdate(primary,primaryLp);if(secondaryLp!=null){secondaryLp.y=primaryLp.y;clamp(secondaryLp,true);safeUpdate(secondary,secondaryLp);}imeRestoreY=null;}
    private void updateLockVisibility(){KeyguardManager km=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);lockHidden=km!=null&&km.isKeyguardLocked()&&!fs.showOnLock();recomputeVisibility();}

    private void recomputeVisibility(){boolean visible=!(manualHidden||screenshotHidden||appHidden||lockHidden||fullscreenHidden);DiagnosticLog.i(this,"VISIBILITY","visible="+visible+" manual="+manualHidden+" shot="+screenshotHidden+" app="+appHidden+" lock="+lockHidden+" full="+fullscreenHidden+" ime="+imeVisible+" notif="+notificationExpanded+" status="+statusBarVisible);int v=visible?View.VISIBLE:View.INVISIBLE;if(primary!=null)primary.setVisibility(v);if(secondary!=null)secondary.setVisibility(v);if(!visible)trail.end();syncWakeViews();updateNotification();}
    private void syncWakeViews(){boolean need=manualHidden&&fs.prefs().getBoolean(FloatSettings.K_HIDE_MAIN_SWIPE,true)&&!screenshotHidden&&!lockHidden; if(need)addWakeViews();else removeWakeViews();}
    private void addWakeViews(){if(wakeLeft!=null)return;int width=dp(18);wakeLeft=new EdgeWakeView(this,true,()->setManualHidden(false));wakeRight=new EdgeWakeView(this,false,()->setManualHidden(false));wakeLeftLp=wakeLp(width,Gravity.LEFT);wakeRightLp=wakeLp(width,Gravity.RIGHT);try{wm.addView(wakeLeft,wakeLeftLp);wm.addView(wakeRight,wakeRightLp);}catch(Throwable t){removeWakeViews();}}
    private WindowManager.LayoutParams wakeLp(int width,int side){WindowManager.LayoutParams lp=new WindowManager.LayoutParams(width,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);lp.gravity=Gravity.TOP|side;return lp;}
    private void removeWakeViews(){if(wakeLeft!=null)try{wm.removeView(wakeLeft);}catch(Throwable ignored){}if(wakeRight!=null)try{wm.removeView(wakeRight);}catch(Throwable ignored){}wakeLeft=wakeRight=null;wakeLeftLp=wakeRightLp=null;}

    public void clickScreenUnderIcon(){DiagnosticLog.i(this,"ACTION","clickUnder x="+Math.round(lastActionX)+" y="+Math.round(lastActionY));LensAccessibilityService a=LensAccessibilityService.get();if(a==null){android.widget.Toast.makeText(this,"需要开启 FloatLens 无障碍服务",android.widget.Toast.LENGTH_SHORT).show();return;}setScreenshotHidden(true);getMainExecutor().execute(()->new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{boolean ok=a.tap(lastActionX,lastActionY);new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->setScreenshotHidden(false),100);if(!ok)android.widget.Toast.makeText(this,"点击下方屏幕失败",android.widget.Toast.LENGTH_SHORT).show();},80));}

    public void refreshAppearance(){fs=new FloatSettings(this);int px=iconPx();if(primary!=null&&primaryLp!=null){primaryLp.width=primaryLp.height=px;primary.setAlpha(fs.alpha());primary.refreshSettings();clamp(primaryLp,true);edgeHide(primaryLp);safeUpdate(primary,primaryLp);}if(secondary!=null&&secondaryLp!=null){secondaryLp.width=secondaryLp.height=px;secondary.setAlpha(fs.alpha());secondary.refreshSettings();clamp(secondaryLp,true);edgeHide(secondaryLp);safeUpdate(secondary,secondaryLp);}syncSecondary();appHidden=fs.shouldHideForPackage(topPackage);updateLockVisibility();updateImeAvoidance();recomputeVisibility();}
    @Override public void onSharedPreferenceChanged(android.content.SharedPreferences p,String key){refreshAppearance();}
    @Override public void onConfigurationChanged(Configuration c){persistPosition();super.onConfigurationChanged(c);imeRestoreY=null;removeIcons();fs=new FloatSettings(this);show();}
    private void registerScreenReceiver(){screenReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){updateLockVisibility();}};IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_SCREEN_OFF);f.addAction(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_USER_PRESENT);if(Build.VERSION.SDK_INT>=33)registerReceiver(screenReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screenReceiver,f);}
    private void removeIcons(){trail.end();removeWakeViews();if(primary!=null)try{wm.removeView(primary);}catch(Throwable ignored){}if(secondary!=null)try{wm.removeView(secondary);}catch(Throwable ignored){}primary=secondary=null;primaryLp=secondaryLp=null;}
    private void safeUpdate(View v,WindowManager.LayoutParams lp){if(v==null)return;try{wm.updateViewLayout(v,lp);}catch(Throwable ignored){}}
    @Override public void onDestroy(){persistPosition();if(circleState!=null)circleState.finish("service_destroy");removeIcons();try{fs.prefs().unregisterOnSharedPreferenceChangeListener(this);}catch(Throwable ignored){}if(screenReceiver!=null)try{unregisterReceiver(screenReceiver);}catch(Throwable ignored){}if(instance==this)instance=null;super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
    private Notification buildNotification(){Intent stop=new Intent(this,FloatService.class).setAction(ACT_STOP),show=new Intent(this,FloatService.class).setAction(ACT_SHOW);PendingIntent stopPi=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE),showPi=PendingIntent.getService(this,3,show,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE),openPi=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);String state=positionMoveArmed?"移动图标位置：拖动后松手保存":manualHidden?"图标已手动隐藏":lockHidden?"锁屏隐藏":fullscreenHidden?"全屏应用隐藏":appHidden?"当前应用按规则隐藏":circleState!=null&&circleState.active()?"Circle: "+circleState.state():notificationExpanded?"通知栏已展开":"点击进入设置";NotificationCompat.Builder b=new NotificationCompat.Builder(this,"floatlens").setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("FloatLens 悬浮图标已运行").setContentText(state).setContentIntent(openPi).setOngoing(true);if(manualHidden)b.addAction(0,"显示图标",showPi);b.addAction(0,"停止",stopPi);return b.build();}
    private void updateNotification(){try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(27,buildNotification());}catch(Throwable ignored){}}
    private void createChannel(){NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.createNotificationChannel(new NotificationChannel("floatlens","FloatLens 悬浮服务",NotificationManager.IMPORTANCE_LOW));}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
