package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public final class ResultOverlay {
    public static void show(Context c,String text,List<String> blocks,Bitmap image){
        FloatSettings fs=new FloatSettings(c);
        WindowManager wm=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
        LinearLayout box=baseBox(c);
        TextView title=title(c,"OCR 结果"); box.addView(title);
        if(fs.ocrShowImage()&&image!=null)addImage(c,box,image,140);
        if(fs.ocrShowText()){
            ScrollView sv=new ScrollView(c);LinearLayout content=new LinearLayout(c);content.setOrientation(LinearLayout.VERTICAL);
            TextView all=candidate(c,text,true);content.addView(all);
            if(!fs.ocrCollapse()&&blocks!=null&&blocks.size()>1){
                TextView h=new TextView(c);h.setText("识别块（点按复制单块）");h.setTextColor(0xFFBBBBBB);h.setPadding(0,dp(c,12),0,dp(c,6));content.addView(h);
                for(String block:blocks){TextView tv=candidate(c,block,false);tv.setOnClickListener(v->copy(c,block));content.addView(tv);}
            }
            sv.addView(content);box.addView(sv,new LinearLayout.LayoutParams(dp(c,340),fs.ocrCollapse()?dp(c,180):dp(c,300)));
        }
        LinearLayout actions=new LinearLayout(c);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button copy=new Button(c);copy.setText("复制全部");Button close=new Button(c);close.setText("关闭");
        actions.addView(copy,new LinearLayout.LayoutParams(0,-2,1));actions.addView(close,new LinearLayout.LayoutParams(0,-2,1));box.addView(actions);
        showWindow(c,wm,box);
        copy.setOnClickListener(v->copy(c,text));
        close.setOnClickListener(v->{closeWindow(wm,box);FloatService f=FloatService.get();if(f!=null)f.onCircleFinished("result_closed");});
    }

    /** Pure ImageView/ImageButton/icon candidate selected from the accessibility View tree. */
    public static void showVisual(Context c,Bitmap image,ViewNodeCandidate view){
        WindowManager wm=(WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
        LinearLayout box=baseBox(c);
        box.addView(title(c,"View / 图标"));
        if(image!=null)addImage(c,box,image,220);

        StringBuilder meta=new StringBuilder();
        if(view!=null){
            meta.append(view.label());
            if(!view.className().isBlank())meta.append("\n").append(view.className());
            if(!view.viewId().isBlank())meta.append("\n").append(view.viewId());
            meta.append("\n").append(view.bounds().toShortString());
        }else meta.append("图片 View");
        TextView info=candidate(c,meta.toString(),true);box.addView(info,new LinearLayout.LayoutParams(dp(c,340),-2));

        LinearLayout actions=new LinearLayout(c);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button save=new Button(c);save.setText("保存图片");
        Button close=new Button(c);close.setText("关闭");
        actions.addView(save,new LinearLayout.LayoutParams(0,-2,1));actions.addView(close,new LinearLayout.LayoutParams(0,-2,1));box.addView(actions);
        showWindow(c,wm,box);
        save.setOnClickListener(v->{if(image!=null)ScreenshotController.save(c,image);});
        close.setOnClickListener(v->closeWindow(wm,box));
    }

    private static LinearLayout baseBox(Context c){
        LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(c,18),dp(c,18),dp(c,18),dp(c,14));box.setBackgroundColor(0xF0202124);return box;
    }
    private static TextView title(Context c,String text){TextView t=new TextView(c);t.setText(text);t.setTextColor(0xFFFFFFFF);t.setTextSize(18);return t;}
    private static void addImage(Context c,LinearLayout box,Bitmap image,int heightDp){ImageView iv=new ImageView(c);iv.setImageBitmap(image);iv.setAdjustViewBounds(true);iv.setScaleType(ImageView.ScaleType.FIT_CENTER);box.addView(iv,new LinearLayout.LayoutParams(dp(c,340),dp(c,heightDp)));}
    private static void showWindow(Context c,WindowManager wm,LinearLayout box){WindowManager.LayoutParams lp=new WindowManager.LayoutParams(dp(c,380),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);lp.gravity=Gravity.CENTER;wm.addView(box,lp);}
    private static void closeWindow(WindowManager wm,LinearLayout box){try{wm.removeView(box);}catch(Throwable ignored){}}
    private static TextView candidate(Context c,String text,boolean selectable){TextView tv=new TextView(c);tv.setText(text);tv.setTextColor(0xFFFFFFFF);tv.setTextSize(16);tv.setTextIsSelectable(selectable);tv.setPadding(dp(c,8),dp(c,8),dp(c,8),dp(c,8));return tv;}
    private static void copy(Context c,String text){ClipboardManager cm=(ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("FloatLens OCR",text));Toast.makeText(c,"已复制",Toast.LENGTH_SHORT).show();}
    private static int dp(Context c,int v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
    private ResultOverlay(){}
}