package com.nas.messageredirect;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.List;

public final class SmsInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    @Override public void onStart() {
        if (arguments != null && "true".equals(arguments.getString("language_flow"))) { testLanguagePicker(); return; }
        if (arguments != null && "true".equals(arguments.getString("localization_flow"))) {
            Bundle result = new Bundle();
            try { LocaleChecks.run(getTargetContext()); result.putString("stream", "PASS: en/zh/ja, regional locales, fallback, validation, persisted statuses, and localized email templates"); finish(-1, result); }
            catch (Throwable error) { result.putString("stream", "FAIL: " + error); finish(0, result); }
            return;
        }
        if (arguments != null && "true".equals(arguments.getString("pairing_flow"))) { testPairingFlow(); return; }
        if (arguments != null && "true".equals(arguments.getString("dialog_flow"))) { testDialogs(); return; }
        if (arguments != null && "true".equals(arguments.getString("render_ui"))) { renderPreview(); return; }
        if (arguments != null && "true".equals(arguments.getString("ui_flow"))) { testSetupFlow(); return; }
        if (arguments != null && "true".equals(arguments.getString("smtp_network_probe"))) { probeSmtpNetwork(); return; }
        if (arguments != null && "true".equals(arguments.getString("send_smtp_test"))) { sendConfiguredSmtpTest(); return; }
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
            testEmailQueueAndSecrets();
            testSmtpTlsRequiredOnDevice();
            testUpgrade();
            testReceivingIdentity();
            testProfiles();
            if (arguments != null && "true".equals(arguments.getString("verify_saved_configuration"))) {
                SyncConfig saved = new SyncConfig(getTargetContext());
                if (SyncConfig.EMAIL.equals(saved.mode())) saved.smtp().validate();
                if (saved.setupComplete()) {
                    ProfileStore profiles = new ProfileStore(saved); profiles.migrate();
                    ProfileStore.Profile active = profiles.active();
                    check(active != null && active.mode().equals(saved.mode()) && active.target().equals(saved.target()), "active saved card matches uploader configuration");
                    if (SyncConfig.EMAIL.equals(saved.mode())) check(active.smtp().password.equals(saved.smtp().password), "active card retained the original encrypted password");
                }
            }
            if (arguments != null && arguments.containsKey("expected")) {
                String expected = arguments.getString("expected");
                boolean found = false;
                try (SmsStore store = new SmsStore(getTargetContext())) {
                    List<SmsStore.Message> rows = store.recent();
                    for (SmsStore.Message row : rows) if (expected.equals(row.body) && "实时接收".equals(row.source)) found = true;
                }
                check(found, "actual SMS broadcast saved complete body: " + expected);
            }
            result.putString("stream", "PASS: duplicate rejection, persistence, HTTP retry/acknowledgement, encrypted SMTP settings, pinned routing, interruption recovery, v2/v3 migration, receiving phone snapshots and dual SIM" + (arguments != null && arguments.containsKey("expected") ? ", real SMS broadcast" : ""));
            finish(-1, result);
        } catch (Throwable error) { result.putString("stream", "FAIL: " + error); finish(0, result); }
        finally { getTargetContext().deleteDatabase("test-messages.db"); }
    }
    private void probeSmtpNetwork() {
        Bundle result = new Bundle();
        StringBuilder report = new StringBuilder();
        try {
            String host = new SyncConfig(getTargetContext()).prefs.getString("smtp_host", "");
            report.append("SMTP DNS:");
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress address : addresses) report.append(' ').append(address.getHostAddress());
            for (int port : new int[]{465, 587, 443}) {
                try (java.net.Socket socket = new java.net.Socket()) {
                    socket.connect(new java.net.InetSocketAddress(host, port), 4000);
                    report.append("; TCP ").append(port).append(" OK");
                } catch (Exception error) { report.append("; TCP ").append(port).append(' ').append(error.getClass().getSimpleName()); }
            }
            result.putString("stream", report.toString()); finish(-1, result);
        } catch (Exception error) { result.putString("stream", "NETWORK FAIL: " + error.getClass().getSimpleName()); finish(0, result); }
    }
    private void sendConfiguredSmtpTest() {
        Bundle result = new Bundle();
        final boolean[] dataStarted = {false};
        String id = "smtp-test-" + java.util.UUID.randomUUID();
        try {
            SyncConfig config = new SyncConfig(getTargetContext());
            check(SyncConfig.EMAIL.equals(config.mode()) && config.enabled(), "email configuration must be enabled");
            SmtpProfile profile = config.smtp(); profile.validate();
            long now = System.currentTimeMillis();
            SmsStore.Message message = new SmsStore.Message("Message Redirect 测试", "这是一封 Message Redirect 邮件转发测试。\n由手机直接通过 SMTP 发送，不包含真实短信。\n测试编号：" + id, now, "手动邮件测试", id, now, false);
            SmtpSender.send(profile, config.deviceId(), message, () -> dataStarted[0] = true);
            result.putString("stream", "PASS: SMTP server accepted one synthetic test email. Test ID: " + id);
            finish(-1, result);
        } catch (Exception error) {
            // Exceptions may contain credentials or message text; expose only type and submission stage.
            StringBuilder types = new StringBuilder(error.getClass().getSimpleName());
            Throwable cause = error.getCause();
            for (int i = 0; cause != null && i < 5; i++, cause = cause.getCause()) types.append(" > ").append(cause.getClass().getSimpleName());
            result.putString("stream", "FAIL: " + types + "; stage=" + (dataStarted[0] ? "submission-result-uncertain; check inbox before retry" : "configuration-connect-TLS-auth") + "; Test ID: " + id);
            finish(0, result);
        }
    }
    private void testUploader() throws Exception {
        SyncConfig config = new SyncConfig(getTargetContext(), "test-sync");
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            for (int mode = 0; mode < 3; mode++) {
                final int responseMode = mode;
                {
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
    private void testEmailQueueAndSecrets() throws Exception {
        SyncConfig config = new SyncConfig(getTargetContext(), "test-sync");
        getTargetContext().deleteDatabase("test-email.db");
        try (SmsStore store = new SmsStore(getTargetContext(), "test-email.db")) {
            SmtpProfile profile = new SmtpProfile("smtp.example.invalid", 465, "ssl", "sender@example.invalid", "fixture-password", "sender@example.invalid", "recipient@example.invalid");
            config.save("", "", true, SyncConfig.EMAIL, profile);
            check(config.enabled(), "SMTP works without NAS token or URL");
            check(config.smtp().password.equals("fixture-password"), "Keystore decryption");
            String encrypted = config.prefs.getString("smtp_password_encrypted", "");
            check(!encrypted.contains("fixture-password"), "not plaintext in preferences");
            check(!encrypted.equals(SecretStore.encrypt("fixture-password")), "random encryption nonce");
            check(config.smtpPassword(profile.host, profile.username, "", true).equals("fixture-password"), "unchanged account preserves saved password");
            boolean blockedReuse = false;
            try { config.smtpPassword("smtp.other.invalid", profile.username, "", true); }
            catch (IllegalArgumentException expected) { blockedReuse = true; }
            check(blockedReuse, "provider switch cannot reuse previous secret");
            blockedReuse = false;
            try { config.smtpPassword(profile.host, "another@example.invalid", "", true); }
            catch (IllegalArgumentException expected) { blockedReuse = true; }
            check(blockedReuse, "account switch cannot reuse previous secret");
            check(config.smtpPassword("smtp.other.invalid", profile.username, "replacement-fixture", true).equals("replacement-fixture"), "explicit replacement allowed");
            check(config.smtpPassword("smtp.other.invalid", profile.username, "", false).isEmpty(), "disabled draft does not leak old secret");
            store.save("10086", "SMTP fixture", 10, "test");
            String id = store.pending().get(0).id;
            store.bindDelivery(id, SyncConfig.EMAIL, profile.to);
            check(store.eligibleCount(SyncConfig.DATABASE, "https://example.invalid") == 0, "mode switch does not send twice");
            check(store.eligibleCount(SyncConfig.EMAIL, "different@example.invalid") == 0, "recipient change does not reroute");
            store.emailSending(id); store.recoverInFlightEmails();
            check(store.uncertainCount() == 1, "interrupted sending recovered to uncertain");
            check(store.eligibleCount(SyncConfig.EMAIL, profile.to) == 0, "uncertain mail not retried automatically");
            store.retryEmail(id);
            check(store.eligibleCount(SyncConfig.EMAIL, profile.to) == 1, "explicit retry allowed");
            store.markUploaded(java.util.Collections.singletonList(id));
            check(store.pendingCount() == 0, "confirmed mail done");
            check(store.recent().get(0).mode.equals(SyncConfig.EMAIL), "completed route preserved");
        } finally { config.prefs.edit().clear().commit(); getTargetContext().deleteDatabase("test-email.db"); }
    }
    private void testUpgrade() {
        String name = "test-upgrade.db"; getTargetContext().deleteDatabase(name);
        try {
            try (android.database.sqlite.SQLiteDatabase db = getTargetContext().openOrCreateDatabase(name, 0, null)) {
                db.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY, sender TEXT NOT NULL, body TEXT NOT NULL, sent_at INTEGER NOT NULL, captured_at INTEGER NOT NULL, source TEXT NOT NULL, message_id TEXT NOT NULL UNIQUE, uploaded_at INTEGER, UNIQUE(sender,body,sent_at))");
                db.execSQL("INSERT INTO messages VALUES (1,'10086','pending',1,2,'test','old-pending',NULL)");
                db.execSQL("INSERT INTO messages VALUES (2,'10086','done',3,4,'test','old-done',5)");
                db.setVersion(2);
            }
            try (SmsStore store = new SmsStore(getTargetContext(), name)) {
                check(store.count() == 2 && store.pendingCount() == 1, "v2 records preserved");
                check(store.eligibleCount(SyncConfig.EMAIL, "fixture@example.invalid") == 1, "unattempted old record follows selected mode");
                check(store.recent().get(0).uploaded && SyncConfig.DATABASE.equals(store.recent().get(0).mode), "old completed record remains complete");
            }
        } finally { getTargetContext().deleteDatabase(name); }
    }
    private void testSmtpTlsRequiredOnDevice() throws Exception {
        SyncConfig config = new SyncConfig(getTargetContext(), "test-sync");
        getTargetContext().deleteDatabase("test-email.db");
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")); SmsStore store = new SmsStore(getTargetContext(), "test-email.db")) {
            server.setSoTimeout(10000);
            config.save("", "", true, SyncConfig.EMAIL, new SmtpProfile("127.0.0.1", server.getLocalPort(), "starttls", "fixture", "fixture-secret", "from@example.invalid", "to@example.invalid"));
            store.save("10086", "TLS fixture", 20, "test");
            Thread responder = new Thread(() -> {
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    java.io.OutputStream output = socket.getOutputStream();
                    output.write("220 fixture\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); output.flush();
                    check(readLine(socket.getInputStream()).startsWith("EHLO"), "Angus SMTP provider on Android");
                    output.write("250-fixture\r\n250 AUTH PLAIN\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); output.flush();
                    try { check(readLine(socket.getInputStream()).equals("QUIT"), "no auth without TLS"); } catch (java.io.EOFException expected) { }
                } catch (Throwable error) { failure.set(error); }
            });
            responder.start();
            check(SmsUploader.upload(getTargetContext(), "test-email.db", "test-sync"), "pre-DATA failure retried");
            responder.join(12000); check(!responder.isAlive(), "SMTP fixture finished");
            if (failure.get() != null) throw new AssertionError(failure.get());
            check(store.pendingCount() == 1 && store.uncertainCount() == 0, "TLS failure preserves safe pending queue");
            check(!config.status().contains("fixture-secret"), "status excludes password");
        } finally { config.prefs.edit().clear().commit(); getTargetContext().deleteDatabase("test-email.db"); }
    }
    private void testReceivingIdentity() {
        SyncConfig config = new SyncConfig(getTargetContext(), "test-identity");
        String name = "test-identity.db"; getTargetContext().deleteDatabase(name);
        try {
            SmtpProfile profile = new SmtpProfile("smtp.example.invalid", 465, "ssl", "from@example.invalid", "fixture", "from@example.invalid", "to@example.invalid");
            boolean rejected = false;
            try { config.completeSetup("", "", SyncConfig.EMAIL, profile, "", "", false); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && !config.setupComplete(), "invalid setup cannot mark complete");
            config.completeSetup("", "", SyncConfig.EMAIL, profile, "+86 138-0000-0000", "", false);
            check(config.setupComplete() && !config.enabled(), "setup can finish paused");
            check(config.receivingNumber(-1).equals("+8613800000000"), "single number works without slot metadata");
            check(config.receivingNumber(1).isEmpty(), "known unconfigured second SIM must not be assigned first SIM number");
            check(PhoneNumbers.clean(null).isEmpty() && PhoneNumbers.clean("Unknown").isEmpty(), "unavailable SIM numbers remain blank");
            check(PhoneNumbers.clean("+86 138-0000-0000").equals("+8613800000000"), "SIM formatting normalized");
            try (SmsStore store = new SmsStore(getTargetContext(), name)) {
                store.save("10086", "same SMS", 42, "test", config.receivingNumber(0));
                config.completeSetup("", "", SyncConfig.EMAIL, profile, "+8613900000000", "+8613700000000", false);
                check(config.receivingNumber(-1).isEmpty(), "dual SIM unknown slot must not guess");
                check(config.receivingNumber(1).equals("+8613700000000"), "second SIM mapped separately");
                check(store.recent().get(0).receivingNumber.equals("+8613800000000"), "number frozen at capture despite settings change");
                check(store.save("10086", "same SMS", 42, "test", config.receivingNumber(1)), "identical SMS on different SIM preserved");
                check(!store.save("10086", "same SMS", 42, "test", config.receivingNumber(1)), "same SIM duplicate rejected");
                check(SmsReceiver.receivingSlot(new android.content.Intent().putExtra("slot", 1)) == 1, "broadcast slot extraction");
            }
            getTargetContext().deleteDatabase(name);
            try (android.database.sqlite.SQLiteDatabase db = getTargetContext().openOrCreateDatabase(name, 0, null)) {
                db.execSQL("CREATE TABLE messages (_id INTEGER PRIMARY KEY, sender TEXT NOT NULL, body TEXT NOT NULL, sent_at INTEGER NOT NULL, captured_at INTEGER NOT NULL, source TEXT NOT NULL, message_id TEXT NOT NULL UNIQUE, uploaded_at INTEGER, delivery_mode TEXT, delivery_target TEXT, delivery_state TEXT NOT NULL DEFAULT 'pending', UNIQUE(sender,body,sent_at))");
                db.execSQL("INSERT INTO messages VALUES (7,'fixture','legacy',1,2,'test','legacy-id',3,'email','to@example.invalid','done')"); db.setVersion(3);
            }
            try (SmsStore store = new SmsStore(getTargetContext(), name)) {
                SmsStore.Message row = store.recent().get(0);
                check(store.count() == 1 && row.uploaded && row.receivingNumber.isEmpty() && row.target.equals("to@example.invalid") && row.id.equals("legacy-id"), "v3 migration preserves history and leaves unknown phone blank");
            }
            config.completeSetup("", "", SyncConfig.EMAIL, profile, "", "+8613700000000", false);
            check(config.receivingNumber(0).isEmpty() && config.receivingNumber(1).equals("+8613700000000"), "single active second slot can be configured without inventing first slot number");
        } finally { config.prefs.edit().clear().commit(); getTargetContext().deleteDatabase(name); }
    }
    /** Exercise the real menus and resend confirmation using an isolated, paused fixture app. */
    private void testDialogs() {
        Bundle result = new Bundle(); android.app.Activity activity = null; String messageId = null; int resultCode = -1;
        try {
            check(getTargetContext().getPackageName().endsWith(".preview"), "dialog checks require isolated preview");
            SyncConfig config = new SyncConfig(getTargetContext());
            check(config.setupComplete() && !config.enabled() && SyncConfig.EMAIL.equals(config.mode()), "paused email fixtures required");
            java.util.Map<String, ?> saved = new java.util.HashMap<>(config.prefs.getAll());
            android.content.Intent intent = new android.content.Intent().setClassName(getTargetContext().getPackageName(), MainActivity.class.getName()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(intent); waitForIdleSync(); final android.app.Activity app = activity;
            runOnMainSync(() -> {
                android.view.View root = app.getWindow().getDecorView();
                view(root, "更多操作", false).performClick(); CrayonDialog menu = currentDialog(app); captureDialog(app, menu, "dialog-menu.png");
                view(menu.getWindow().getDecorView(), "使用说明", false).performClick();
                check(!menu.isShowing(), "menu dismissed before opening help");
                CrayonDialog help = currentDialog(app); captureDialog(app, help, "dialog-help.png");
                android.widget.ScrollView helpScroll = (android.widget.ScrollView) ((android.view.ViewGroup) help.findViewById(android.R.id.content)).getChildAt(0);
                helpScroll.setSmoothScrollingEnabled(false); helpScroll.fullScroll(android.view.View.FOCUS_DOWN);
                captureDialog(app, help, "dialog-help-bottom.png");
                android.view.View close = view(help.getWindow().getDecorView(), "知道了", false);
                int bottom = helpScroll.getChildAt(0).getTop() + close.getBottom() - helpScroll.getScrollY();
                check(bottom <= helpScroll.getHeight() - helpScroll.getPaddingBottom(), "help button fully reachable after scrolling: y=" + helpScroll.getScrollY() + ", button bottom=" + bottom + ", viewport=" + helpScroll.getHeight());
                view(help.getWindow().getDecorView(), "知道了", false).performClick(); check(!help.isShowing(), "help action closes dialog");
                view(root, "管理转发配置", false).performClick(); view(root, "编辑配置", false).performClick();
                view(root, "自定义／保留当前参数  ▾", false).performClick();
                CrayonDialog providers = currentDialog(app); captureDialog(app, providers, "dialog-providers.png");
                view(providers.getWindow().getDecorView(), "QQ 邮箱", false).performClick();
                check(!providers.isShowing(), "provider menu closes after choosing");
                check(((android.widget.EditText) view(root, "SMTP 服务器", true)).getText().toString().equals("smtp.qq.com"), "choice retains the correct provider callback");
                view(root, "取消，返回配置列表", false).performClick();
                check(saved.equals(config.prefs.getAll()), "cancel after choosing provider preserves saved configuration");
                view(root, "返回首页", false).performClick();
            });
            DeliveryLock.LOCK.lock();
            try (SmsStore store = new SmsStore(getTargetContext())) {
                String body = "dialog-fixture-" + java.util.UUID.randomUUID(); store.save("fixture-sender", body, 1234567, "fixture");
                for (SmsStore.Message row : store.pending()) if (body.equals(row.body)) messageId = row.id;
                check(messageId != null, "fixture inserted");
                store.bindDelivery(messageId, SyncConfig.EMAIL, config.target()); store.emailSending(messageId); store.emailUncertain(messageId);
            } finally { DeliveryLock.LOCK.unlock(); }
            runOnMainSync(() -> {
                android.view.View root = app.getWindow().getDecorView(); view(root, "更多操作", false).performClick();
                view(currentDialog(app).getWindow().getDecorView(), "检查邮件是否收到", false).performClick();
            });
            final long deadline = android.os.SystemClock.uptimeMillis() + 5000;
            while ((currentDialog(app) == null || !currentDialog(app).isShowing()) && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(20);
            runOnMainSync(() -> {
                CrayonDialog review = currentDialog(app); captureDialog(app, review, "dialog-review.png");
                view(review.getWindow().getDecorView(), "没收到，重发", false).performClick();
                CrayonDialog confirm = currentDialog(app); check(!review.isShowing(), "review closes before nested confirmation"); captureDialog(app, confirm, "dialog-resend.png");
                view(confirm.getWindow().getDecorView(), "先不重发", false).performClick();
                check(!confirm.isShowing(), "cancel closes resend confirmation");
            });
            try (SmsStore store = new SmsStore(getTargetContext())) {
                boolean stillNeedsReview = false;
                for (SmsStore.Message row : store.uncertain()) if (row.id.equals(messageId)) stillNeedsReview = true;
                check(stillNeedsReview, "cancel never schedules a duplicate email");
            }
            runOnMainSync(() -> {
                view(app.getWindow().getDecorView(), "更多操作", false).performClick(); CrayonDialog menu = currentDialog(app); menu.onBackPressed(); check(!menu.isShowing(), "back dismisses menu");
            });
            result.putString("stream", "PASS: styled menus, scrollable help with reachable actions, provider callback, cancel preserves configuration, nested resend cancellation and back dismissal; six dialog previews captured");
        } catch (Throwable error) { result.putString("stream", "FAIL: " + error); resultCode = 0; }
        finally {
            if (messageId != null) { try (SmsStore store = new SmsStore(getTargetContext())) { store.getWritableDatabase().delete("messages", "message_id=?", new String[]{messageId}); } }
            if (activity != null) { final android.app.Activity app = activity; runOnMainSync(app::finish); }
        }
        finish(resultCode, result);
    }
    private CrayonDialog currentDialog(android.app.Activity activity) {
        try { java.lang.reflect.Field field = MainActivity.class.getDeclaredField("activeDialog"); field.setAccessible(true); return (CrayonDialog) field.get(activity); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private void captureDialog(android.app.Activity activity, CrayonDialog dialog, String filename) {
        check(dialog != null && dialog.isShowing(), "dialog visible for capture");
        android.view.View root = dialog.getWindow().getDecorView();
        if (root.getWidth() == 0 || root.getHeight() == 0) {
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(dialog.getWindow().getAttributes().width, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(activity.getResources().getDisplayMetrics().heightPixels, android.view.View.MeasureSpec.AT_MOST));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        }
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888); root.draw(new android.graphics.Canvas(image));
        try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(), filename))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
        finally { image.recycle(); }
    }
    /** Render the actual Android views for visual QA even when the device screen sleeps. */
    private void renderPreview() {
        Bundle result = new Bundle(); android.app.Activity activity = null;
        try {
            check(getTargetContext().getPackageName().endsWith(".preview"), "rendering requires isolated preview");
            SyncConfig config = new SyncConfig(getTargetContext());
            check(config.setupComplete() && !config.enabled(), "rendering requires paused fixture configuration");
            android.content.Intent intent = new android.content.Intent().setClassName(getTargetContext().getPackageName(), MainActivity.class.getName()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(intent); waitForIdleSync(); final android.app.Activity app = activity;
            runOnMainSync(() -> {
                android.view.View root = app.getWindow().getDecorView();
                renderView(app, root, "crayon-home.png");
                view(root, app.getString(R.string.manage_profiles), false).performClick();
                renderView(app, root, "crayon-cards.png");
                view(root, app.getString(R.string.edit_profile), false).performClick();
                renderView(app, root, "crayon-editor.png");
            });
            result.putString("stream", "PASS: rendered native home, cards and editor from paused preview fixtures"); finish(-1, result);
        } catch (Throwable error) { result.putString("stream", "FAIL: " + error); finish(0, result); }
        finally { if (activity != null) { final android.app.Activity app = activity; runOnMainSync(app::finish); } }
    }
    private void testLanguagePicker() {
        Bundle result = new Bundle(); android.app.Activity activity = null;
        String original = AppLanguage.selected(getTargetContext());
        try {
            check(getTargetContext().getPackageName().endsWith(".preview"), "language picker test requires isolated preview");
            SyncConfig config = new SyncConfig(getTargetContext());
            check(config.setupComplete() && !config.enabled(), "language test requires paused fixtures");
            java.util.Map<String, ?> saved = new java.util.HashMap<>(config.prefs.getAll());
            android.content.Intent intent = new android.content.Intent().setClassName(getTargetContext().getPackageName(), MainActivity.class.getName()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            for (String tag : new String[]{"en", "ja", "zh-CN", ""}) {
                activity = startActivitySync(intent); waitForIdleSync();
                final android.app.Activity app = activity;
                int label = tag.equals("en") ? R.string.language_english : tag.equals("ja") ? R.string.language_japanese : tag.isEmpty() ? R.string.language_system : R.string.language_chinese;
                runOnMainSync(() -> {
                    android.view.View languageButton = view(app.getWindow().getDecorView(), app.getString(R.string.language_title), true);
                    check(languageButton instanceof android.widget.Button && ((android.widget.Button) languageButton).getText().toString().equals(AppLanguage.badge(app)), "compact current-language flag and code");
                    languageButton.performClick();
                    CrayonDialog menu = currentDialog(app);
                    android.view.View option = view(menu.getWindow().getDecorView(), app.getString(label), false);
                    if (option == null) option = view(menu.getWindow().getDecorView(), "✓ " + app.getString(label), false);
                    check(option != null, "language choice is visible"); option.performClick();
                });
                waitForIdleSync();
                check(AppLanguage.selected(getTargetContext()).equals(tag), "language choice persisted: " + tag);
                activity = startActivitySync(intent); waitForIdleSync();
                final android.app.Activity localized = activity;
                runOnMainSync(() -> {
                    check(localized.getString(R.string.next).equals(tag.equals("en") ? "Next" : tag.equals("ja") ? "次へ" : "下一步"), "selected language renders after recreation");
                    check(view(localized.getWindow().getDecorView(), localized.getString(R.string.manage_profiles), false) != null, "home renders in selected language");
                    if (tag.equals("ja")) {
                        view(localized.getWindow().getDecorView(), localized.getString(R.string.language_title), true).performClick();
                        captureDialog(localized, currentDialog(localized), "language-picker.png");
                    }
                    localized.finish();
                });
                check(saved.equals(config.prefs.getAll()), "language choice preserves forwarding configuration");
                runOnMainSync(app::finish);
            }
            result.putString("stream", "PASS: visible language button, English/Japanese/Chinese/system selection, recreation, persistence, and forwarding settings unchanged"); finish(-1, result);
        } catch (Throwable error) { result.putString("stream", "FAIL: " + error); finish(0, result); }
        finally {
            if (activity != null) { final android.app.Activity app = activity; runOnMainSync(() -> { AppLanguage.select(app, original); app.finish(); }); }
        }
    }
    private void renderView(android.app.Activity activity, android.view.View root, String filename) {
        android.util.DisplayMetrics display = new android.util.DisplayMetrics(); activity.getWindowManager().getDefaultDisplay().getMetrics(display);
        int width = display.widthPixels, height = display.heightPixels;
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(), filename))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        finally { bitmap.recycle(); }
    }
    private android.view.View view(android.view.View root, String text, boolean description) {
        if (description ? text.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription()) : root instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) root).getText())) return root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) { android.view.View found = view(group.getChildAt(i), text, description); if (found != null) return found; }
        }
        return null;
    }
    private void testProfiles() {
        SyncConfig config = new SyncConfig(getTargetContext(), "test-profiles");
        getTargetContext().deleteDatabase("test-profiles.db");
        try (SmsStore queue = new SmsStore(getTargetContext(), "test-profiles.db")) {
            SmtpProfile first = new SmtpProfile("smtp.example.invalid", 465, "ssl", "first@example.invalid", "first-fixture-secret", "first@example.invalid", "one@example.invalid");
            config.completeSetup("https://service.example.invalid", "fixture-token-xxxxxxxxxxxxxxxxxxxxxxxx", SyncConfig.EMAIL, first, "+8613800000000", "", false);
            java.util.Map<String, ?> legacy = new java.util.HashMap<>(config.prefs.getAll());
            ProfileStore profiles = new ProfileStore(config); profiles.migrate();
            check(profiles.list().size() == 2 && profiles.active().mode().equals(SyncConfig.EMAIL), "legacy mail and database migrate to independent cards");
            for (java.util.Map.Entry<String, ?> entry : legacy.entrySet()) check(entry.getValue().equals(config.prefs.getAll().get(entry.getKey())), "migration preserves original active settings and ciphertext");
            String firstId = profiles.activeId(); profiles.migrate(); check(profiles.list().size() == 2, "migration is idempotent");
            SmtpProfile second = new SmtpProfile("smtp.example.invalid", 465, "ssl", "second@example.invalid", "second-fixture-secret", "second@example.invalid", "two@example.invalid");
            profiles.save("", "Second mailbox", SyncConfig.EMAIL, "", "", second);
            String secondId = profiles.list().get(2).id();
            check(profiles.activeId().equals(firstId) && config.smtp().password.equals(first.password), "new card does not activate or overwrite current credentials");
            String json = config.prefs.getString("profiles_v1", "");
            check(!json.contains(first.password) && !json.contains(second.password), "per-profile passwords are encrypted");
            queue.save("10086", "profile fixture", 12, "test", config.phone(0));
            queue.bindDelivery(queue.pending().get(0).id, SyncConfig.EMAIL, first.to);
            profiles.select(secondId);
            check(config.smtp().password.equals(second.password) && config.target().equals(second.to) && !config.enabled() && config.phone(0).equals("+8613800000000"), "selection restores correct credentials, preserving pause and phone");
            check(queue.eligibleCount(config.mode(), config.target()) == 0, "selection cannot reroute an attempted message");
            check(profiles.password(firstId, first.host, first.username, "").equals(first.password), "editing an inactive card uses that card's own saved password");
            profiles.save(firstId, "First mailbox", SyncConfig.EMAIL, "", "", first);
            check(config.target().equals(second.to) && config.smtp().password.equals(second.password), "editing inactive card leaves current route unchanged");
            profiles.select(firstId);
            check(config.smtp().password.equals(first.password) && queue.eligibleCount(config.mode(), config.target()) == 1, "returning to original card restores its pinned queue");
            java.util.Map<String, ?> before = new java.util.HashMap<>(config.prefs.getAll()); boolean rejected = false;
            try { profiles.save("", "Invalid", SyncConfig.DATABASE, "not-a-service-url", "bad", null); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && before.equals(config.prefs.getAll()), "invalid card cannot partially update stored or active configuration");
        } catch (Exception error) { throw new AssertionError(error); }
        finally { config.prefs.edit().clear().commit(); getTargetContext().deleteDatabase("test-profiles.db"); }
    }
    private void testSetupFlow() {
        Bundle result = new Bundle();
        android.app.Activity activity = null;
        try {
            check(getTargetContext().getPackageName().endsWith(".preview"), "UI test requires isolated preview package");
            getTargetContext().getSharedPreferences("sync", 0).edit().clear().commit();
            android.content.Intent intent = new android.content.Intent().setClassName(getTargetContext().getPackageName(), MainActivity.class.getName()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            final android.app.Activity app = activity;
            runOnMainSync(() -> {
                android.view.View root = app.getWindow().getDecorView();
                check(view(root, "短信，送到你需要的地方", false) != null, "fresh launch shows setup");
                check(view(root, "斑鸠 · 邮件转发", true) != null, "fresh setup starts with the default pigeon");
                java.util.Map<String, ?> beforeChoice = new java.util.HashMap<>(new SyncConfig(getTargetContext()).prefs.getAll());
                view(root, "自建数据库", false).performClick();
                check(view(root, "赛博斑鸠 · 自建数据库", true) != null, "wizard mode choice immediately previews cyber costume");
                view(root, "邮件转发", false).performClick();
                check(view(root, "斑鸠 · 邮件转发", true) != null && beforeChoice.equals(new SyncConfig(getTargetContext()).prefs.getAll()), "switching preview back restores default without saving draft routing");
                view(root, "下一步", false).performClick();
                view(root, "下一步", false).performClick();
                check(view(root, "请填写接收短信的本机号码", false) != null, "wizard rejects missing receiving number");
                ((android.widget.EditText) view(root, "本机号码 / SIM 1", true)).setText("+8613800000000");
                view(root, "下一步", false).performClick();
                ((android.widget.EditText) view(root, "SMTP 服务器", true)).setText("smtp.example.invalid");
                ((android.widget.EditText) view(root, "发件邮箱 / SMTP 账号", true)).setText("from@example.invalid");
                ((android.widget.EditText) view(root, "邮箱授权码 / 应用专用密码", true)).setText("fixture-secret");
                ((android.widget.EditText) view(root, "收件邮箱", true)).setText("to@example.invalid");
                view(root, "下一步", false).performClick();
                check(view(root, "准备好接收新短信", false) != null, "valid mail setup reaches review");
                ((android.widget.CheckBox) view(root, "完成后开启自动转发", false)).setChecked(false);
                view(root, "完成设置", false).performClick();
                check(view(root, "转发状态", false) != null, "completed setup shows status home");
                SyncConfig config = new SyncConfig(getTargetContext());
                check(config.setupComplete() && !config.enabled() && config.phone(0).equals("+8613800000000"), "wizard atomically saves paused setup and number");
                view(root, "管理转发配置", false).performClick();
                check(view(root, "转发配置", false) != null && !hasInput(root), "saved configuration list contains cards, no form inputs");
                ProfileStore profiles = new ProfileStore(config); String mailId = profiles.activeId();
                check(profiles.list().size() == 1, "first setup migrated to one card");
                view(root, "＋ 新增配置", false).performClick();
                view(root, "自建数据库", false).performClick();
                check(view(root, "赛博斑鸠 · 自建数据库", true) != null, "new database editor previews armor");
                ((android.widget.EditText) view(root, "配置名称", true)).setText("Fixture database");
                ((android.widget.EditText) view(root, "转存服务地址", true)).setText("https://service.example.invalid");
                ((android.widget.EditText) view(root, "服务访问令牌", true)).setText("fixture-token-xxxxxxxxxxxxxxxxxxxxxxxx");
                view(root, "保存配置", false).performClick();
                check(profiles.list().size() == 2 && profiles.activeId().equals(mailId) && !hasInput(root), "adding a saved card does not change active profile or open its form");
                check(view(root, "斑鸠 · 邮件转发", true) != null, "saving an inactive database card restores the active email appearance");
                view(view(root, "选择配置：Fixture database", true), "编辑配置", false).performClick();
                check(view(root, "赛博斑鸠 · 自建数据库", true) != null, "inactive editor previews its own mode");
                view(root, "取消，返回配置列表", false).performClick();
                check(view(root, "斑鸠 · 邮件转发", true) != null && SyncConfig.EMAIL.equals(config.mode()), "cancelled preview restores active mascot and leaves route unchanged");
                view(root, "选择配置：Fixture database", true).performClick();
                check(SyncConfig.DATABASE.equals(config.mode()) && !config.enabled() && config.phone(0).equals("+8613800000000") && !hasInput(root), "choosing card switches directly without a form, preserving paused state and phone");
                check(view(root, "赛博斑鸠 · 自建数据库", true) != null, "saved database selection changes the active mascot");
                view(root, "返回首页", false).performClick(); renderView(app, root, "mascot-database-home.png");
                view(root, "更多操作", false).performClick(); CrayonDialog dbMenu = currentDialog(app);
                check(view(dbMenu.getWindow().getDecorView(), "赛博斑鸠 · 自建数据库", true) != null, "database menu shares the selected costume");
                captureDialog(app, dbMenu, "mascot-database-menu.png"); checkAbout(app, dbMenu, true); view(root, "管理转发配置", false).performClick();
                ProfileStore.Profile mail = profiles.find(mailId);
                view(root, "选择配置：" + mail.name(), true).performClick();
                check(SyncConfig.EMAIL.equals(config.mode()) && profiles.activeId().equals(mailId) && !hasInput(root), "selecting mail card directly restores its saved settings");
                check(view(root, "斑鸠 · 邮件转发", true) != null, "switching back to email removes the cyber costume");
                view(root, "返回首页", false).performClick(); renderView(app, root, "mascot-email-home.png");
                view(root, "更多操作", false).performClick(); CrayonDialog mailMenu = currentDialog(app);
                check(view(mailMenu.getWindow().getDecorView(), "斑鸠 · 邮件转发", true) != null, "email menu restores the default pigeon");
                captureDialog(app, mailMenu, "mascot-email-menu.png"); checkAbout(app, mailMenu, false); view(root, "管理转发配置", false).performClick();
                android.view.View mailCard = view(root, "选择配置：" + mail.name(), true);
                view(mailCard, "编辑配置", false).performClick();
                check(((android.widget.EditText) view(root, "邮箱授权码 / 应用专用密码", true)).getText().length() == 0, "stored password is not displayed");
                java.util.Map<String, ?> beforeEdit = new java.util.HashMap<>(config.prefs.getAll());
                ((android.widget.EditText) view(root, "收件邮箱", true)).setText("draft@example.invalid");
                view(root, "取消，返回配置列表", false).performClick();
                check(beforeEdit.equals(config.prefs.getAll()) && !hasInput(root), "cancel returns to cards and leaves saved configuration unchanged");
                view(view(root, "选择配置：" + mail.name(), true), "编辑配置", false).performClick();
                ((android.widget.EditText) view(root, "收件邮箱", true)).setText("new@example.invalid");
                view(root, "保存配置", false).performClick();
                check(config.target().equals("new@example.invalid") && config.smtp().password.equals("fixture-secret") && !hasInput(root), "save keeps password and returns to cards rather than expanded form");
            });
            result.putString("stream", "PASS: first-run wizard, mode costumes, About installed version/build and both return actions, saved cards, explicit edit only, cancel and password preservation"); finish(-1, result);
        } catch (Throwable error) { result.putString("stream", "FAIL: " + error); finish(0, result); }
        finally { if (activity != null) { final android.app.Activity app = activity; runOnMainSync(app::finish); } }
    }
    private void checkAbout(android.app.Activity app, CrayonDialog menu, boolean database) {
        SyncConfig config = new SyncConfig(getTargetContext());
        java.util.Map<String, ?> before = new java.util.HashMap<>(config.prefs.getAll());
        view(menu.getWindow().getDecorView(), "关于咕咕咕", false).performClick();
        check(!menu.isShowing(), "menu closes before About page");
        android.view.View root = app.getWindow().getDecorView();
        check(view(root, "关于咕咕咕", false) != null && !hasInput(root), "About is a separate read-only page");
        try {
            android.content.pm.PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
            check(view(root, installed.versionName, false) != null && view(root, "构建号  " + code, false) != null, "version and build match installed package");
        } catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new AssertionError(error); }
        check(view(root, database ? "赛博斑鸠 · 自建数据库" : "斑鸠 · 邮件转发", true) != null, "About pigeon follows active route");
        renderView(app, root, database ? "about-database.png" : "about-email.png");
        if (database) app.onBackPressed(); else view(root, "返回首页", false).performClick();
        check(view(root, "转发状态", false) != null && before.equals(config.prefs.getAll()), "About returns home without changing configuration");
    }
    private void testPairingFlow() {
        Bundle result = new Bundle(); android.app.Activity activity = null;
        try {
            check(getTargetContext().getPackageName().endsWith(".preview"), "pairing requires isolated preview app");
            SyncConfig config = new SyncConfig(getTargetContext());
            check(config.setupComplete() && !config.enabled(), "pairing fixtures must be paused");
            java.io.File fixtures = getTargetContext().getExternalFilesDir(null);
            org.json.JSONObject expected;
            try (java.io.InputStream input = new java.io.FileInputStream(new java.io.File(fixtures, "pairing-expected.json"))) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                expected = new org.json.JSONObject(bytes.toString("UTF-8"));
            }
            android.graphics.Bitmap image = android.graphics.BitmapFactory.decodeFile(new java.io.File(fixtures, "pairing-fixture.jpg").getAbsolutePath());
            check(image != null, "browser-rendered QR fixture available");
            int width = image.getWidth(), height = image.getHeight(); int[] pixels = new int[width * height]; image.getPixels(pixels, 0, width, 0, 0, width, height); image.recycle();
            java.util.Map<com.google.zxing.DecodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);
            hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, true); hints.put(com.google.zxing.DecodeHintType.CHARACTER_SET, "UTF-8");
            String raw = new com.google.zxing.qrcode.QRCodeReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(width, height, pixels))), hints).getText();
            PairingConfig pairing = PairingConfig.parse(raw);
            check(pairing.url.equals(expected.getString("url")) && pairing.token.equals(expected.getString("api_token")), "real browser QR decodes to current backend URL and rotated key");
            for (String invalid : new String[]{"https://example.invalid", "{\"type\":\"other\"}", raw.replace("\"version\":1", "\"version\":2"), raw.replace("https://", "javascript://"), raw.replace(pairing.token, "short")}) {
                boolean rejected = false; try { PairingConfig.parse(invalid); } catch (IllegalArgumentException error) { rejected = true; }
                check(rejected, "malformed, unknown and unsafe QR contents rejected");
            }
            final String contents = raw;
            activity = startActivitySync(new android.content.Intent().setClassName(getTargetContext().getPackageName(), MainActivity.class.getName()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitForIdleSync(); final MainActivity app = (MainActivity) activity;
            runOnMainSync(() -> {
                android.view.View root = app.getWindow().getDecorView();
                view(root, "管理转发配置", false).performClick(); view(root, "＋ 新增配置", false).performClick(); view(root, "自建数据库", false).performClick();
                check(view(root, "扫码配置", false) != null, "database editor has an explicit scan entry");
                java.util.Map<String, ?> before = new java.util.HashMap<>(config.prefs.getAll());
                String active = new ProfileStore(config).activeId();
                app.onActivityResult(41, android.app.Activity.RESULT_OK, new android.content.Intent().putExtra("pairing", contents));
                CrayonDialog preview = currentDialog(app); captureDialog(app, preview, "pairing-confirm.png");
                check(view(preview.getWindow().getDecorView(), "连接这个小窝？", false) != null, "scan shows confirmation first");
                view(preview.getWindow().getDecorView(), "取消", false).performClick();
                check(before.equals(config.prefs.getAll()) && ((android.widget.EditText) view(root, "转存服务地址", true)).getText().length() == 0, "cancel does not change draft or saved settings");
                app.onActivityResult(41, android.app.Activity.RESULT_OK, new android.content.Intent().putExtra("pairing", contents));
                view(currentDialog(app).getWindow().getDecorView(), "填入配置", false).performClick();
                check(pairing.url.equals(((android.widget.EditText) view(root, "转存服务地址", true)).getText().toString()), "confirmation fills service URL");
                check(pairing.token.equals(((android.widget.EditText) view(root, "服务访问令牌", true)).getText().toString()), "confirmation fills current key");
                check(before.equals(config.prefs.getAll()), "scan confirmation does not save or switch active route");
                renderView(app, root, "pairing-filled-editor.png");
                view(root, "保存配置", false).performClick();
                ProfileStore profiles = new ProfileStore(config);
                check(view(root, "选择配置：咕咕咕服务", true) != null && active.equals(profiles.activeId()) && !config.enabled(), "explicit save creates card and preserves active route and paused state");
            });
            result.putString("stream", "PASS: browser-generated QR decoded on Android, current rotated key matches, invalid data rejected, cancel preserved settings, confirm fills draft only, explicit save preserves active route"); finish(-1, result);
        } catch (Throwable error) { result.putString("stream", "FAIL: " + error); finish(0, result); }
        finally { if (activity != null) { final android.app.Activity app = activity; runOnMainSync(app::finish); } }
    }
    private boolean hasInput(android.view.View root) {
        if (root instanceof android.widget.EditText) return true;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) if (hasInput(group.getChildAt(i))) return true;
        }
        return false;
    }
    private String readLine(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); int value;
        while ((value=input.read()) != -1) { if (value == 10) return bytes.toString("US-ASCII").replace("\r", ""); bytes.write(value); }
        throw new java.io.EOFException();
    }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
