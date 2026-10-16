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
    static boolean upload(Context context) { return upload(context, "messages.db", "sync"); }
    static boolean upload(Context context, String database, String preferences) {
        DeliveryLock.LOCK.lock();
        try {
            SyncConfig config = new SyncConfig(context, preferences);
            if (!config.enabled()) return false;
            try (SmsStore store = new SmsStore(context, database)) {
                store.recoverInFlightEmails();
                int total = 0;
                for (int batchIndex = 0; batchIndex < 8; batchIndex++) {
                    if (Thread.currentThread().isInterrupted() || !new SyncConfig(context, preferences).enabled()) return true;
                    List<SmsStore.Message> rows = store.pending(config.mode(), config.target());
                    if (rows.isEmpty()) {
                        if (total > 0) config.status((SyncConfig.EMAIL.equals(config.mode()) ? "发件服务器已接收本次 " + total + " 封邮件" : "本次已保存 " + total + " 条短信到数据库") + " · " + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date()));
                        return false;
                    }
                    for (SmsStore.Message row : rows) store.bindDelivery(row.id, config.mode(), config.target());
                    if (SyncConfig.EMAIL.equals(config.mode())) {
                        SmtpProfile smtp = config.smtp();
                        for (SmsStore.Message row : rows) {
                            if (Thread.currentThread().isInterrupted()) return true;
                            final boolean[] dataStarted = {false};
                            try {
                                SmtpSender.send(smtp, config.deviceId(), row, () -> {
                                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("sending stopped");
                                    store.emailSending(row.id); dataStarted[0] = true;
                                });
                                store.markUploaded(java.util.Collections.singletonList(row.id)); total++;
                                config.status("发件服务器已接收 " + total + " 封邮件，请以收件箱实际收到为准。");
                            } catch (Exception error) {
                                if (dataStarted[0]) {
                                    store.emailUncertain(row.id);
                                    config.status("发送时连接中断，邮件可能已经寄出。请到“更多操作 → 检查邮件是否收到”核对，这封邮件不会自动重发。");
                                    return store.eligibleCount(config.mode(), config.target()) > 0;
                                }
                                config.status("暂时无法连接发件邮箱，或邮箱验证未通过。短信已保留，会稍后重试；请检查网络、邮箱账号和授权码。");
                                return true;
                            }
                        }
                        continue;
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
                        if (code != 200) { config.status("保存到数据库未成功（HTTP " + code + "）。短信已保留，会稍后重试；请检查服务地址和访问令牌。"); return true; }
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
                        config.status("本次已保存 " + total + " 条短信到数据库。");
                    } finally { connection.disconnect(); }
                }
                return store.eligibleCount(config.mode(), config.target()) > 0;
            } catch (Exception e) {
                // Never persist exception text: URLs, authentication, or message contents may be included.
                config.status("这次转发未完成。短信已保留在手机，会稍后重试；请检查网络和转发配置。");
                return true;
            } finally {
                context.sendBroadcast(new Intent(SmsStore.CHANGED).setPackage(context.getPackageName()));
            }
        } finally { DeliveryLock.LOCK.unlock(); }
    }
}
