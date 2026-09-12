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
            case ActionId.SCREENSHOT -> {
                OcrEngine.invalidatePending(c, "action_screenshot");
                ScreenshotController.capture(c,false);
            }
            case ActionId.REGION_SCREENSHOT -> {
                OcrEngine.invalidatePending(c, "action_region_screenshot");
                ScreenshotController.capture(c,true);
            }
            case ActionId.OCR -> {
                OcrEngine.invalidatePending(c, "action_ocr_selection");
                ViewSelectionOverlay.show(c);
            }
            case ActionId.AI_SCREEN -> {
                OcrEngine.invalidatePending(c, "action_circle_select");
                DiagnosticLog.i(c,"AI_SCREEN","enter Circle Select workspace");
                CircleSelectController.show(c);
            }
            case ActionId.MOVE_ICON -> { FloatService f=FloatService.get(); if(f!=null) f.armPositionMove(); }
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
