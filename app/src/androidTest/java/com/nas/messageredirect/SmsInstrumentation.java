package com.nas.messageredirect;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.List;

public final class SmsInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getTargetContext().deleteDatabase("test-messages.db");
            try (SmsStore store = new SmsStore(getTargetContext(), "test-messages.db")) {
                check(store.save("10086", "第一条短信", 1234, "实时接收"), "first save");
                check(!store.save("10086", "第一条短信", 1234, "历史读取"), "duplicate rejected");
                check(store.save("10086", "不同短信", 1235, "实时接收"), "different message");
                check(store.count() == 2, "count");
                check(store.recent().get(0).body.equals("不同短信"), "newest first");
            }
            try (SmsStore store = new SmsStore(getTargetContext(), "test-messages.db")) { check(store.count() == 2, "persistent across reopen"); }
            testUploader();
            if (arguments != null && arguments.containsKey("expected")) {
                String expected = arguments.getString("expected");
                boolean found = false;
                try (SmsStore store = new SmsStore(getTargetContext())) {
                    List<SmsStore.Message> rows = store.recent();
                    for (SmsStore.Message row : rows) if (expected.equals(row.body) && "实时接收".equals(row.source)) found = true;
                }
                check(found, "actual SMS broadcast saved complete body: " + expected);
            }
            result.putString("stream", "PASS: duplicate rejection, ordering, persistence, upload failure/retry/acknowledgement" + (arguments != null && arguments.containsKey("expected") ? ", real SMS broadcast" : ""));
            finish(-1, result);
        } catch (Throwable error) { result.putString("stream", "FAIL: " + error); finish(0, result); }
        finally { getTargetContext().deleteDatabase("test-messages.db"); }
    }
    private void testUploader() throws Exception {
        SyncConfig config = new SyncConfig(getTargetContext(), "test-sync");
        try {
            for (int mode = 0; mode < 3; mode++) {
                final int responseMode = mode;
                try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                    server.setSoTimeout(10000);
                    config.save("http://127.0.0.1:" + server.getLocalPort(), "test-token-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", true);
                    final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                    Thread responder = new Thread(() -> {
                        try (java.net.Socket socket = server.accept()) {
                            socket.setSoTimeout(10000);
                            java.io.InputStream input = socket.getInputStream();
                            String first = readLine(input); check(first.startsWith("POST /api/sms "), "POST endpoint");
                            int length = 0; String line; boolean authenticated = false;
                            while (!(line = readLine(input)).isEmpty()) {
                                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                                if (line.equals("Authorization: Bearer " + config.token())) authenticated = true;
                            }
                            check(authenticated, "API authentication");
                            byte[] bytes = new byte[length]; int read = 0;
                            while (read < length) { int n = input.read(bytes, read, length-read); if (n < 0) throw new java.io.EOFException(); read += n; }
                            org.json.JSONObject payload = new org.json.JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                            org.json.JSONArray rows = payload.getJSONArray("messages"); check(rows.length() == 2, "pending batch size");
                            org.json.JSONArray accepted = new org.json.JSONArray();
                            if (responseMode == 2) for (int i=0;i<rows.length();i++) accepted.put(rows.getJSONObject(i).getString("message_id"));
                            String response = new org.json.JSONObject().put("accepted", accepted).toString();
                            byte[] responseBody = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            String header = "HTTP/1.1 " + (responseMode == 0 ? "503 Service Unavailable" : "200 OK") + "\r\nContent-Type: application/json\r\nContent-Length: " + responseBody.length + "\r\nConnection: close\r\n\r\n";
                            socket.getOutputStream().write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); socket.getOutputStream().write(responseBody); socket.getOutputStream().flush();
                        } catch (Throwable error) { failure.set(error); }
                    });
                    responder.start();
                    boolean retry = SmsUploader.upload(getTargetContext(), "test-messages.db", "test-sync");
                    responder.join(12000); check(!responder.isAlive(), "mock server finished");
                    if (failure.get() != null) throw new AssertionError(failure.get());
                    try (SmsStore store = new SmsStore(getTargetContext(), "test-messages.db")) {
                        check(store.pendingCount() == (mode == 2 ? 0 : 2), "only confirmed messages marked uploaded");
                        check(retry == (mode != 2), "retry decision");
                    }
                }
            }
        } finally { config.prefs.edit().clear().commit(); }
    }
    private String readLine(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); int value;
        while ((value=input.read()) != -1) { if (value == 10) return bytes.toString("US-ASCII").replace("\r", ""); bytes.write(value); }
        throw new java.io.EOFException();
    }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
