package com.nas.messageredirect;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Named local configurations; the selected profile is projected onto the existing uploader settings. */
final class ProfileStore {
    private static final String PROFILES = "profiles_v1", ACTIVE = "active_profile_id";
    private final SyncConfig config;
    ProfileStore(SyncConfig config) { this.config = config; }
    static final class Profile {
        final JSONObject data;
        Profile(JSONObject data) { this.data = data; }
        String id() { return data.optString("id"); }
        String name() { return data.optString("name"); }
        String mode() { return data.optString("mode"); }
        String get(String key) { return data.optString(key); }
        int port() { return data.optInt("smtp_port", 465); }
        String target() { return SyncConfig.EMAIL.equals(mode()) ? get("smtp_to") : get("url"); }
        SmtpProfile smtp() { return new SmtpProfile(get("smtp_host"), port(), get("smtp_security"), get("smtp_username"), SecretStore.decrypt(get("smtp_password_encrypted")), get("smtp_from"), get("smtp_to")); }
    }
    List<Profile> list() {
        List<Profile> result = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(config.prefs.getString(PROFILES, "[]"));
            for (int i = 0; i < rows.length(); i++) result.add(new Profile(rows.getJSONObject(i)));
            return result;
        } catch (Exception error) { throw new IllegalArgumentException("本地配置读取失败，请保留应用数据后重试"); }
    }
    Profile find(String id) { for (Profile profile : list()) if (profile.id().equals(id)) return profile; return null; }
    String activeId() { return config.prefs.getString(ACTIVE, ""); }
    Profile active() { return find(activeId()); }
    private void lock() {
        try { if (!DeliveryLock.LOCK.tryLock(200, TimeUnit.MILLISECONDS)) throw new IllegalArgumentException("正在发送，请稍后切换或编辑配置"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("操作已中断，请重试"); }
    }
    void migrate() {
        if (config.prefs.contains(PROFILES)) return;
        lock();
        try {
            if (config.prefs.contains(PROFILES)) return;
            List<Profile> rows = new ArrayList<>();
            if (!config.prefs.getString("smtp_host", "").isEmpty() && !config.prefs.getString("smtp_to", "").isEmpty()) rows.add(snapshot(SyncConfig.EMAIL));
            if (!config.url().isEmpty() && !config.token().isEmpty()) rows.add(snapshot(SyncConfig.DATABASE));
            SharedPreferences.Editor editor = config.prefs.edit();
            for (Profile profile : rows) if (profile.mode().equals(config.mode())) editor.putString(ACTIVE, profile.id());
            commit(editor.putString(PROFILES, encode(rows)));
        } finally { DeliveryLock.LOCK.unlock(); }
    }
    private Profile snapshot(String mode) {
        JSONObject data = new JSONObject();
        put(data, "id", UUID.randomUUID().toString()); put(data, "mode", mode);
        if (SyncConfig.EMAIL.equals(mode)) {
            String host = config.prefs.getString("smtp_host", "");
            put(data, "name", SmtpPresets.matching(host).host.isEmpty() ? "邮件转发" : SmtpPresets.matching(host).name);
            for (String key : MAIL_KEYS) put(data, key, config.prefs.getString(key, ""));
            put(data, "smtp_port", config.prefs.getInt("smtp_port", 465));
        } else { put(data, "name", "自建数据库"); put(data, "url", config.url()); put(data, "token", config.token()); }
        return new Profile(data);
    }
    private static final String[] MAIL_KEYS = {"smtp_host", "smtp_security", "smtp_username", "smtp_password_encrypted", "smtp_from", "smtp_to"};
    String password(String id, String host, String username, String replacement) {
        SmtpPresets.requirePasswordAuth(host);
        if (!replacement.isEmpty()) return replacement;
        Profile profile = find(id);
        if (profile != null && profile.get("smtp_host").equalsIgnoreCase(host.trim()) && profile.get("smtp_username").equals(username.trim()))
            return SecretStore.decrypt(profile.get("smtp_password_encrypted"));
        throw new IllegalArgumentException("新增配置或更换账号、服务器时，请填写邮箱授权码");
    }
    void save(String id, String name, String mode, String url, String token, SmtpProfile smtp) {
        lock();
        try {
            if (name.trim().isEmpty()) throw new IllegalArgumentException("请给配置起一个名称");
            if (!SyncConfig.EMAIL.equals(mode) && !SyncConfig.DATABASE.equals(mode)) throw new IllegalArgumentException("配置方式不正确");
            JSONObject data = new JSONObject();
            String savedId = id.isEmpty() ? UUID.randomUUID().toString() : id;
            if (!id.isEmpty() && find(id) == null) throw new IllegalArgumentException("配置已不存在，请返回列表刷新");
            put(data, "id", savedId); put(data, "name", name.trim()); put(data, "mode", mode);
            if (SyncConfig.EMAIL.equals(mode)) {
                if (smtp == null) throw new IllegalArgumentException("请填写邮箱配置");
                smtp.validate();
                put(data, "smtp_host", smtp.host); put(data, "smtp_port", smtp.port); put(data, "smtp_security", smtp.security);
                put(data, "smtp_username", smtp.username); put(data, "smtp_password_encrypted", SecretStore.encrypt(smtp.password));
                put(data, "smtp_from", smtp.from); put(data, "smtp_to", smtp.to);
            } else {
                url = url.trim(); token = token.trim();
                try {
                    URI uri = new URI(url);
                    if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new Exception();
                } catch (Exception error) { throw new IllegalArgumentException("请填写完整的 HTTP/HTTPS 配套服务地址"); }
                if (token.length() < 32) throw new IllegalArgumentException("服务访问令牌至少需要 32 位");
                while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                put(data, "url", url); put(data, "token", token);
            }
            Profile replacement = new Profile(data);
            List<Profile> rows = list(); boolean replaced = false;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(savedId)) { rows.set(i, replacement); replaced = true; break; }
            if (!replaced) rows.add(replacement);
            SharedPreferences.Editor editor = config.prefs.edit().putString(PROFILES, encode(rows));
            // Editing another card or adding a card must not change the currently running configuration.
            if (savedId.equals(activeId())) apply(editor, replacement);
            commit(editor);
        } finally { DeliveryLock.LOCK.unlock(); }
    }
    void select(String id) {
        lock();
        try {
            Profile profile = find(id);
            if (profile == null) throw new IllegalArgumentException("配置不存在，请刷新后重试");
            if (SyncConfig.EMAIL.equals(profile.mode())) profile.smtp().validate();
            else if (profile.get("token").length() < 32) throw new IllegalArgumentException("请先编辑配置并填写有效的服务访问令牌");
            SharedPreferences.Editor editor = config.prefs.edit().putString(ACTIVE, id).putString("status", "已切换配置。收到新短信后自动转发，尚未发送的短信会继续尝试。");
            apply(editor, profile); commit(editor);
        } finally { DeliveryLock.LOCK.unlock(); }
    }
    private void apply(SharedPreferences.Editor editor, Profile profile) {
        editor.putString("delivery_mode", profile.mode());
        if (SyncConfig.EMAIL.equals(profile.mode())) {
            for (String key : MAIL_KEYS) editor.putString(key, profile.get(key));
            editor.putInt("smtp_port", profile.port());
        } else editor.putString("url", profile.get("url")).putString("token", profile.get("token"));
    }
    private static String encode(List<Profile> profiles) { JSONArray array = new JSONArray(); for (Profile profile : profiles) array.put(profile.data); return array.toString(); }
    private static void put(JSONObject data, String key, Object value) { try { data.put(key, value); } catch (Exception error) { throw new IllegalArgumentException("配置内容无法保存"); } }
    private static void commit(SharedPreferences.Editor editor) { if (!editor.commit()) throw new IllegalArgumentException("配置保存失败，请重试"); }
}
