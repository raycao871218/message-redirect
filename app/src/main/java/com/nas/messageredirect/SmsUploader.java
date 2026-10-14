package com.nas.messageredirect;

import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SmsUploader {
    private static final Object LOCK = new Object();
    static boolean upload(Context context) { return upload(context, "messages.db", "sync"); }
    static boolean upload(Context context, String database, String preferences) {
        synchronized (LOCK) {
            SyncConfig config = new SyncConfig(context, preferences);
            if (!config.enabled()) return false;
            try (SmsStore store = new SmsStore(context, database)) {
                int total = 0;
                for (int batchIndex = 0; batchIndex < 8; batchIndex++) {
                    if (Thread.currentThread().isInterrupted() || !new SyncConfig(context, preferences).enabled()) return true;
                    List<SmsStore.Message> rows = store.pending();
                    if (rows.isEmpty()) {
                        if (total > 0) config.status("上传成功，本次 " + total + " 条 · " + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date()));
                        return false;
                    }
                    JSONObject payload = new JSONObject(); payload.put("device_id", config.deviceId());
                    JSONArray messages = new JSONArray(); Set<String> sent = new HashSet<>();
                    for (SmsStore.Message row : rows) {
                        JSONObject message = new JSONObject();
                        message.put("message_id", row.id); message.put("sender", row.sender); message.put("body", row.body);
                        message.put("sent_at", row.time); message.put("captured_at", row.capturedAt); message.put("source", row.source);
                        messages.put(message); sent.add(row.id);
                    }
                    payload.put("messages", messages);
                    byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                    HttpURLConnection connection = (HttpURLConnection) new URL(config.url() + "/api/sms").openConnection();
                    try {
                        connection.setConnectTimeout(8000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
                        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        connection.setRequestProperty("Authorization", "Bearer " + config.token());
                        try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
                        int code = connection.getResponseCode();
                        if (code != 200) { config.status("上传未完成（HTTP " + code + "），记录保留待重试"); return true; }
                        ByteArrayOutputStream response = new ByteArrayOutputStream();
                        try (InputStream input = connection.getInputStream()) {
                            byte[] buffer = new byte[4096]; int read;
                            while ((read = input.read(buffer)) != -1) {
                                if (response.size() + read > 65536) throw new IllegalStateException("response too large");
                                response.write(buffer, 0, read);
                            }
                        }
                        JSONArray accepted = new JSONObject(response.toString("UTF-8")).getJSONArray("accepted");
                        List<String> confirmed = new ArrayList<>();
                        for (int i = 0; i < accepted.length(); i++) {
                            String id = accepted.getString(i);
                            if (!sent.remove(id)) throw new IllegalStateException("invalid acknowledgement");
                            confirmed.add(id);
                        }
                        if (!sent.isEmpty()) throw new IllegalStateException("incomplete acknowledgement");
                        if (Thread.currentThread().isInterrupted()) return true;
                        store.markUploaded(confirmed); total += confirmed.size();
                        config.status("已上传 " + total + " 条，本地仍有 " + store.pendingCount() + " 条待上传");
                    } finally { connection.disconnect(); }
                }
                return store.pendingCount() > 0;
            } catch (Exception e) {
                // Never persist exception text: URLs, authentication, or message contents may be included.
                config.status("连接或确认失败，短信保留在本机，等待重试");
                return true;
            } finally {
                context.sendBroadcast(new Intent(SmsStore.CHANGED).setPackage(context.getPackageName()));
            }
        }
    }
}
