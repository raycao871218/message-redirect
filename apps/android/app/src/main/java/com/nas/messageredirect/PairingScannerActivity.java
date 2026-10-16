package com.nas.messageredirect;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import java.util.Collections;

/** Local-only QR camera; scanning is opt-in and the camera stops whenever this screen leaves. */
public final class PairingScannerActivity extends Activity {
    private DecoratedBarcodeView scanner;
    private TextView hint;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20), dp(16), dp(20), dp(24));
        page.setBackground(CrayonTexture.paper(0xfffcf6e8, getResources().getDisplayMetrics().density));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        MascotView dove = new MascotView(this); dove.setMode(SyncConfig.DATABASE, false); header.addView(dove, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView title = new TextView(this); title.setText("扫一扫，找到小窝"); title.setTextSize(22); title.setTextColor(0xff3b3a49); header.addView(title); page.addView(header);
        hint = new TextView(this); hint.setText("对准后端面板上的配置二维码。扫码后会先预览，不会自动修改配置。"); hint.setTextColor(0xff706b75); hint.setTextSize(14); hint.setPadding(0, dp(12), 0, dp(18)); page.addView(hint);
        scanner = new DecoratedBarcodeView(this); scanner.setStatusText(""); scanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        page.addView(scanner, new LinearLayout.LayoutParams(-1, 0, 1));
        scanner.getBarcodeView().addStateListener(new CameraPreview.StateListener() {
            public void previewSized() {} public void previewStarted() {} public void previewStopped() {} public void cameraClosed() {}
            public void cameraError(Exception error) { hint.setText("相机暂时无法使用。可以返回后重试，或手动填写服务地址和访问密钥。"); }
        });
        scanner.decodeSingle(new BarcodeCallback() {
            @Override public void barcodeResult(BarcodeResult result) {
                scanner.pause(); setResult(RESULT_OK, new Intent().putExtra("pairing", result.getText())); finish();
            }
        });
        Button cancel = new Button(this); cancel.setText("取消，返回设置"); cancel.setAllCaps(false); cancel.setTextColor(0xff3b3a49); cancel.setMinHeight(dp(50));
        cancel.setBackground(new SketchDrawable(0xfffffcf4, 0xff8a8a9a, 1.6f, getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(-1, -2); action.topMargin = dp(18); page.addView(cancel, action); cancel.setOnClickListener(view -> finish()); setContentView(page);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA}, 42);
    }
    @Override protected void onResume() { super.onResume(); if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanner.resume(); }
    @Override protected void onPause() { scanner.pause(); super.onPause(); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 42 && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanner.resume();
        else hint.setText("未授予相机权限。可返回手动填写配置；需要扫码时，在系统权限设置中允许使用相机。");
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
