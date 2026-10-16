package com.nas.messageredirect;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.util.UUID;

final class SyncConfig {
    static final String DATABASE = "database", EMAIL = "email";
    final SharedPreferences prefs;
    SyncConfig(Context context) { this(context, "sync"); }
    SyncConfig(Context context, String name) { prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE); }
    String url() { return prefs.getString("url", ""); }
    String token() { return prefs.getString("token", ""); }
    String mode() { return EMAIL.equals(prefs.getString("delivery_mode", DATABASE)) ? EMAIL : DATABASE; }
    String modeLabel() { return EMAIL.equals(mode()) ? "邮件转发" : "自建数据库"; }
    String target() { return EMAIL.equals(mode()) ? prefs.getString("smtp_to", "") : url(); }
    boolean setupComplete() { return prefs.getBoolean("setup_complete", false); }
    String phone(int slot) { return prefs.getString(slot == 1 ? "receiving_phone_2" : "receiving_phone_1", ""); }
    String receivingNumber(int slot) {
        if (slot == 0 || slot == 1) return phone(slot);
        if (phone(1).isEmpty()) return phone(0);
        return phone(0).isEmpty() ? phone(1) : "";
    }
    String phoneSummary() {
        String first = phone(0).isEmpty() ? "" : "SIM 1  " + phone(0);
        String second = phone(1).isEmpty() ? "" : "SIM 2  " + phone(1);
        return first + (first.isEmpty() || second.isEmpty() ? "" : "\n") + second;
    }
    void completeSetup(String address, String token, String mode, SmtpProfile smtp, String first, String second, boolean enabled) {
        first = normalizePhone(first); second = normalizePhone(second);
        if (first.isEmpty() && second.isEmpty()) throw new IllegalArgumentException("请填写接收短信的本机号码");
        if (!first.isEmpty() && first.equals(second)) throw new IllegalArgumentException("两张 SIM 卡请填写不同号码");
        saveInternal(address, token, enabled, mode, smtp, first, second);
    }
    static String normalizePhone(String phone) {
        String clean = phone.trim().replace(" ", "").replace("-", "").replace("(", "").replace(")", "");
        if (!clean.isEmpty() && !clean.matches("\\+?[0-9]{5,20}")) throw new IllegalArgumentException("手机号需为 5–20 位数字，可带国际区号 +");
        return clean;
    }
    boolean enabled() { return prefs.getBoolean("enabled", false) && (EMAIL.equals(mode()) || !token().isEmpty()); }
    SmtpProfile smtp() {
        return new SmtpProfile(prefs.getString("smtp_host", ""), prefs.getInt("smtp_port", 465),
            prefs.getString("smtp_security", "ssl"), prefs.getString("smtp_username", ""),
            SecretStore.decrypt(prefs.getString("smtp_password_encrypted", "")),
            prefs.getString("smtp_from", ""), prefs.getString("smtp_to", ""));
    }
    String smtpPassword(String host, String username, String replacement, boolean enabled) {
        if (enabled) SmtpPresets.requirePasswordAuth(host);
        if (!replacement.isEmpty()) return replacement;
        boolean unchanged = host.trim().equalsIgnoreCase(prefs.getString("smtp_host", "")) && username.trim().equals(prefs.getString("smtp_username", ""));
        if (unchanged) return SecretStore.decrypt(prefs.getString("smtp_password_encrypted", ""));
        if (enabled && !prefs.getString("smtp_password_encrypted", "").isEmpty())
            throw new IllegalArgumentException("SMTP 服务器或账号已更换，请重新填写密码／授权码");
        return "";
    }
    synchronized String deviceId() {
        String id = prefs.getString("device_id", "");
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); prefs.edit().putString("device_id", id).commit(); }
        return id;
    }
    void save(String address, String token, boolean enabled) {
        save(address, token, enabled, DATABASE, null);
    }
    void save(String address, String token, boolean enabled, String mode, SmtpProfile smtp) {
        saveInternal(address, token, enabled, mode, smtp, null, null);
    }
    private void saveInternal(String address, String token, boolean enabled, String mode, SmtpProfile smtp, String first, String second) {
        try {
            // A status refresh may briefly recover interrupted rows; allow that transaction to finish.
            if (!DeliveryLock.LOCK.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS)) throw new IllegalArgumentException("正在发送短信，请稍后再修改配置");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalArgumentException("保存已中断，请重试");
        }
        try {
            if (!DATABASE.equals(mode) && !EMAIL.equals(mode)) throw new IllegalArgumentException("请选择转存方式");
            address = address.trim(); token = token.trim();
            if (DATABASE.equals(mode)) {
                URI uri;
                try { uri = new URI(address); } catch (Exception e) { throw new IllegalArgumentException("接口地址格式不正确"); }
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                    throw new IllegalArgumentException("请输入完整的 HTTP/HTTPS 服务地址");
                if (enabled && token.length() < 32) throw new IllegalArgumentException("请填写服务端访问令牌（至少 32 位）");
            }
            while (address.endsWith("/")) address = address.substring(0, address.length() - 1);
            if (EMAIL.equals(mode) && enabled) { if (smtp == null) throw new IllegalArgumentException("请填写 SMTP 配置"); smtp.validate(); }
            android.content.SharedPreferences.Editor editor = prefs.edit().putString("url", address).putString("token", token)
                .putBoolean("enabled", enabled).putString("delivery_mode", mode);
            if (smtp != null) editor.putString("smtp_host", smtp.host).putInt("smtp_port", smtp.port)
                .putString("smtp_security", smtp.security).putString("smtp_username", smtp.username)
                .putString("smtp_password_encrypted", SecretStore.encrypt(smtp.password))
                .putString("smtp_from", smtp.from).putString("smtp_to", smtp.to);
            if (first != null) editor.putString("receiving_phone_1", first).putString("receiving_phone_2", second).putBoolean("setup_complete", true);
            if (!editor.commit()) throw new IllegalArgumentException("配置保存失败，请重试");
        } finally { DeliveryLock.LOCK.unlock(); }
    }
    String status() {
        String status = prefs.getString("status", "收到新短信后会自动转发。");
        // Show clearer wording for statuses saved by an earlier version, without rewriting settings.
        if (status.equals("尚未发送") || status.equals("已切换配置，等待新短信或待处理记录")) return "收到新短信后会自动转发，尚未发送的短信会继续尝试。";
        if (status.startsWith("邮件发送结果待确认")) return "有邮件没能确认是否寄出。请到“更多操作 → 检查邮件是否收到”核对。";
        if (status.equals("SMTP 连接、证书或认证失败，短信保留待重试；请检查邮件配置")) return "暂时无法连接发件邮箱，或邮箱验证未通过。请检查网络、邮箱账号和授权码，短信会稍后重试。";
        return status;
    }
    void status(String value) { prefs.edit().putString("status", value).apply(); }
}
