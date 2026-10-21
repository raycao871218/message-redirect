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
                        if (total > 0) config.status(SyncConfig.EMAIL.equals(config.mode()) ? R.string.status_mail_accepted : R.string.status_archived, total);
                        return false;
                    }
                    for (SmsStore.Message row : rows) store.bindDelivery(row.id, config.mode(), config.target());
                    if (SyncConfig.EMAIL.equals(config.mode())) {
                        SmtpProfile smtp = config.smtp();
                        for (SmsStore.Message row : rows) {
                            if (Thread.currentThread().isInterrupted()) return true;
                            final boolean[] dataStarted = {false};
                            try {
                                SmtpSender.send(context, smtp, config.deviceId(), row, () -> {
                                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("sending stopped");
                                    store.emailSending(row.id); dataStarted[0] = true;
                                });
                                store.markUploaded(java.util.Collections.singletonList(row.id)); total++;
                                config.status(R.string.status_mail_accepted, total);
                            } catch (Exception error) {
                                if (dataStarted[0]) {
                                    store.emailUncertain(row.id);
                                    config.status(R.string.the_connection_dropped_while_sending_the_email_may_already_have_b);
                                    return store.eligibleCount(config.mode(), config.target()) > 0;
                                }
                                config.status(R.string.could_not_connect_or_sign_in_to_your_email_provider_sms_is_kept_f);
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
                        if (code != 200) { config.status(R.string.status_http_error, code); return true; }
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
                        config.status(R.string.status_archived, total);
                    } finally { connection.disconnect(); }
                }
                return store.eligibleCount(config.mode(), config.target()) > 0;
            } catch (Exception e) {
                // Never persist exception text: URLs, authentication, or message contents may be included.
                config.status(R.string.forwarding_was_not_completed_sms_is_kept_on_the_phone_for_retry_c);
                return true;
            } finally {
                context.sendBroadcast(new Intent(SmsStore.CHANGED).setPackage(context.getPackageName()));
            }
        } finally { DeliveryLock.LOCK.unlock(); }
    }
}
