package com.nas.messageredirect;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.Telephony;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private TextView status, result, syncStatus;
    private LinearLayout messages;
    private Button importButton, uploadButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean registered;
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20), dp(28), dp(20), dp(20));
        scroll.addView(root); setContentView(scroll);
        TextView title = text("短信转存", 28); title.setTextColor(Color.rgb(23, 74, 61)); root.addView(title);
        root.addView(text("接收短信并转存到 NAS。\n上传失败时保留本地记录，连接恢复后重试。", 15));
        status = text("", 16); root.addView(status);
        button(root, "授权接收新短信", v -> requestSms(Manifest.permission.RECEIVE_SMS));
        importButton = button(root, "读取最近 100 条已有短信", v -> {
            if (!allowed(Manifest.permission.READ_SMS)) requestSms(Manifest.permission.READ_SMS); else importInbox();
        });
        button(root, "刷新本地记录", v -> refresh());
        button(root, "打开应用权限设置", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        syncStatus = text("", 15); root.addView(syncStatus);
        uploadButton = button(root, "立即上传待传短信", v -> uploadNow());
        SyncConfig config = new SyncConfig(this);
        LinearLayout serverPanel = new LinearLayout(this); serverPanel.setOrientation(LinearLayout.VERTICAL);
        serverPanel.setVisibility(View.GONE);
        button(root, "服务器配置", v -> serverPanel.setVisibility(serverPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        root.addView(serverPanel);
        serverPanel.addView(text("短信接收服务地址", 14));
        EditText address = new EditText(this); address.setSingleLine(true); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); address.setText(config.url()); serverPanel.addView(address);
        serverPanel.addView(text("服务访问令牌", 14));
        EditText token = new EditText(this); token.setSingleLine(true); token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); token.setText(config.token()); serverPanel.addView(token);
        CheckBox enabled = new CheckBox(this); enabled.setText("自动上传新短信和本地待传记录"); enabled.setChecked(config.enabled()); serverPanel.addView(enabled);
        button(serverPanel, "保存服务器配置", v -> {
            try {
                config.save(address.getText().toString(), token.getText().toString(), enabled.isChecked());
                android.app.job.JobScheduler jobs = (android.app.job.JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
                jobs.cancel(UploadJobService.IMMEDIATE); jobs.cancel(UploadJobService.PERIODIC);
                UploadJobService.schedule(this); result.setText("服务器配置已保存"); serverPanel.setVisibility(View.GONE); refresh();
            } catch (IllegalArgumentException e) { result.setText(e.getMessage()); }
        });
        result = text("测试方法：授权后，用另一部手机向本机发送普通短信。可返回桌面再发送，之后打开本 App 查看“实时接收”记录。", 14); root.addView(result);
        messages = new LinearLayout(this); messages.setOrientation(LinearLayout.VERTICAL); root.addView(messages);
    }
    private boolean allowed(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private void requestSms(String permission) {
        if (allowed(permission)) { refresh(); return; }
        requestPermissions(new String[]{permission}, permission.equals(Manifest.permission.READ_SMS) ? 2 : 1);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == 2 && allowed(Manifest.permission.READ_SMS)) importInbox();
        else if (grants.length > 0 && grants[0] != PackageManager.PERMISSION_GRANTED) result.setText("权限未授予。如果系统不再弹窗，请打开应用权限设置；部分系统或安装器可能限制短信权限。");
        refresh();
    }
    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(SmsStore.CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changed, filter, Context.RECEIVER_NOT_EXPORTED); else registerLegacyReceiver(filter);
        registered = true; UploadJobService.schedule(this); refresh();
    }
    // Older Android releases lack RECEIVER_NOT_EXPORTED; a signature permission
    // restricts this UI refresh broadcast to apps signed with our certificate.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter filter) {
        registerReceiver(changed, filter, getPackageName() + ".INTERNAL", null);
    }
    @Override protected void onPause() { if (registered) { unregisterReceiver(changed); registered = false; } super.onPause(); }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
    private void refresh() {
        status.setText("新短信接收权限：" + (allowed(Manifest.permission.RECEIVE_SMS) ? "已授权" : "未授权") + "\n已有短信读取权限：" + (allowed(Manifest.permission.READ_SMS) ? "已授权" : "未授权"));
        worker.execute(() -> {
            try (SmsStore store = new SmsStore(this)) {
                java.util.List<SmsStore.Message> rows = store.recent(); long count = store.count(), pending = store.pendingCount();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    SyncConfig config = new SyncConfig(this);
                    syncStatus.setText("自动上传：" + (config.enabled() ? "已开启" : "未开启") + " · 待上传 " + pending + " 条\n" + config.status());
                    messages.removeAllViews(); messages.addView(text("本地共 " + count + " 条 · 显示最近 100 条", 17));
                    if (rows.isEmpty()) messages.addView(text("暂无短信记录，等待测试短信到达。", 15));
                    for (SmsStore.Message row : rows) {
                        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(14), dp(12), dp(14), dp(12)); card.setBackgroundColor(Color.WHITE);
                        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); messages.addView(card, layout);
                        card.addView(text(row.sender + " · " + row.source, 17));
                        card.addView(text(row.uploaded ? "已转存到服务器" : "待上传", 12));
                        card.addView(text(DateFormat.getDateTimeInstance().format(new Date(row.time)), 12));
                        TextView body = text(row.body, 16); body.setTextIsSelectable(true); card.addView(body);
                    }
                });
            }
        });
    }
    private void importInbox() {
        importButton.setEnabled(false); result.setText("正在读取短信收件箱…");
        worker.execute(() -> {
            String outcome;
            try (SmsStore store = new SmsStore(this); Cursor c = getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI, new String[]{"address", "body", "date", "date_sent"}, null, null, "date DESC")) {
                if (c == null) throw new IllegalStateException("短信收件箱不可用");
                int read = 0, added = 0;
                while (read < 100 && c.moveToNext()) { read++; if (store.save(c.getString(0), c.getString(1), c.getLong(3) > 0 ? c.getLong(3) : c.getLong(2), "历史读取")) added++; }
                outcome = "已读取 " + read + " 条，新增 " + added + " 条本地记录。";
            } catch (SecurityException e) { outcome = "系统拒绝读取短信，请检查短信权限。"; }
            catch (Exception e) { outcome = "读取失败，设备可能没有短信收件箱。"; }
            UploadJobService.schedule(this);
            String message = outcome;
            runOnUiThread(() -> { if (isDestroyed()) return; importButton.setEnabled(true); result.setText(message); refresh(); });
        });
    }
    private void uploadNow() {
        if (!new SyncConfig(this).enabled()) { result.setText("请先在服务器配置中填写令牌并开启上传"); return; }
        uploadButton.setEnabled(false); result.setText("正在上传待传短信…");
        worker.execute(() -> {
            boolean retry = SmsUploader.upload(getApplicationContext());
            if (retry) UploadJobService.schedule(getApplicationContext());
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                uploadButton.setEnabled(true); result.setText(new SyncConfig(this).status()); refresh();
            });
        });
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(Color.rgb(40, 51, 47)); v.setPadding(0, dp(6), 0, dp(6)); return v; }
    private Button button(LinearLayout root, String label, View.OnClickListener action) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(action); root.addView(b, new LinearLayout.LayoutParams(-1, -2)); return b; }
}
