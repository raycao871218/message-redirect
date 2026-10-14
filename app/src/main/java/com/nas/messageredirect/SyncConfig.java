package com.nas.messageredirect;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.util.UUID;

final class SyncConfig {
    final SharedPreferences prefs;
    SyncConfig(Context context) { this(context, "sync"); }
    SyncConfig(Context context, String name) { prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE); }
    String url() { return prefs.getString("url", ""); }
    String token() { return prefs.getString("token", ""); }
    boolean enabled() { return prefs.getBoolean("enabled", false) && !token().isEmpty(); }
    synchronized String deviceId() {
        String id = prefs.getString("device_id", "");
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); prefs.edit().putString("device_id", id).commit(); }
        return id;
    }
    void save(String address, String token, boolean enabled) {
        address = address.trim(); token = token.trim();
        URI uri;
        try { uri = new URI(address); } catch (Exception e) { throw new IllegalArgumentException("接口地址格式不正确"); }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("请输入完整的 HTTP/HTTPS 服务地址");
        if (enabled && token.length() < 32) throw new IllegalArgumentException("请填写服务端访问令牌（至少 32 位）");
        while (address.endsWith("/")) address = address.substring(0, address.length() - 1);
        prefs.edit().putString("url", address).putString("token", token).putBoolean("enabled", enabled).commit();
    }
    String status() { return prefs.getString("status", "尚未上传"); }
    void status(String value) { prefs.edit().putString("status", value).apply(); }
}
