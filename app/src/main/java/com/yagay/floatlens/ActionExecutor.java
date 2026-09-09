package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.widget.Toast;

public final class ActionExecutor {
    public static void execute(Context c,String a){
        LensAccessibilityService s=LensAccessibilityService.get();
        DiagnosticLog.i(c,"ACTION","execute="+a+" accessibility="+(s!=null));
        switch(a){
            case ActionId.BACK -> global(c,s,AccessibilityService.GLOBAL_ACTION_BACK);
            case ActionId.HOME -> global(c,s,AccessibilityService.GLOBAL_ACTION_HOME);
            case ActionId.RECENTS -> global(c,s,AccessibilityService.GLOBAL_ACTION_RECENTS);
            case ActionId.NOTIFICATIONS -> global(c,s,AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
            case ActionId.SCREENSHOT -> ScreenshotController.capture(c,false);
            case ActionId.REGION_SCREENSHOT -> ScreenshotController.capture(c,true);
            case ActionId.OCR -> ScreenshotController.captureForOcr(c);
            case ActionId.AI_SCREEN -> AiScreenController.show(c);
            case ActionId.CLICK_UNDER -> { FloatService f=FloatService.get(); if(f!=null) f.clickScreenUnderIcon(); }
            case ActionId.HIDE -> {
                FloatService f = FloatService.get();
                if (f != null) f.setManualHidden(true);
            }
            default -> {}
        }
    }
    private static void global(Context c,LensAccessibilityService s,int a){if(s==null||!s.global(a))Toast.makeText(c,"请先开启 FloatLens 无障碍服务",Toast.LENGTH_SHORT).show();}
    private ActionExecutor(){}
}
