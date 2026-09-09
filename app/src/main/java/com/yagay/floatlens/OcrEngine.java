package com.yagay.floatlens;

import android.content.ClipData; import android.content.ClipboardManager; import android.content.Context; import android.graphics.Bitmap; import android.widget.Toast;
import com.google.mlkit.vision.common.InputImage; import com.google.mlkit.vision.text.Text; import com.google.mlkit.vision.text.TextRecognition; import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions; import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList; import java.util.List;

/** OCR pipeline: bitmap -> ML Kit text -> candidate blocks -> result overlay. */
public final class OcrEngine {
 public static void recognize(Context c, Bitmap b){Context app=c.getApplicationContext(); FloatService service=FloatService.get(); if(service!=null)service.onCircleRecognizeStarted(); FloatSettings fs=new FloatSettings(app); TextRecognizer client=fs.ocrType()==1?TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS):TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build()); DiagnosticLog.i(app,"OCR_PIPELINE","start type="+fs.ocrType()+" bitmap="+(b==null?"null":b.getWidth()+"x"+b.getHeight())); client.process(InputImage.fromBitmap(b,0)).addOnSuccessListener(t->{try{String full=t.getText();if(full==null||full.isBlank()){if(service!=null)service.onCircleFinished("ocr_empty");Toast.makeText(app,"未识别到文字",Toast.LENGTH_SHORT).show();return;}List<String> blocks=new ArrayList<>();for(Text.TextBlock block:t.getTextBlocks())if(block.getText()!=null&&!block.getText().isBlank())blocks.add(block.getText());DiagnosticLog.i(app,"OCR_PIPELINE","success chars="+full.length()+" candidates="+blocks.size());if(service!=null)service.onOcrResults(blocks.size());ClipboardManager cm=(ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("FloatLens OCR",full));ResultOverlay.show(app,full,blocks,b);}finally{client.close();}}).addOnFailureListener(e->{client.close();DiagnosticLog.i(app,"OCR_PIPELINE","failure="+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));if(service!=null)service.onCircleFinished("ocr_failed");Toast.makeText(app,"OCR失败: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),Toast.LENGTH_LONG).show();}); }
 private OcrEngine(){}
}
