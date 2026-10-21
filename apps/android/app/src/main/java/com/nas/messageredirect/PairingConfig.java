package com.nas.messageredirect;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import org.json.JSONObject;

/** Versioned, data-only pairing format. Never opens a scanned URL or executes QR contents. */
final class PairingConfig {
    final String name, url, token;
    private PairingConfig(String name, String url, String token) { this.name = name; this.url = url; this.token = token; }
    static PairingConfig parse(String raw) {
        try {
            if (raw == null || raw.length() > 8192) throw new IllegalArgumentException();
            JSONObject value = new JSONObject(raw);
            HashSet<String> allowed = new HashSet<>(Arrays.asList("type", "version", "name", "url", "api_token"));
            for (Iterator<String> keys = value.keys(); keys.hasNext();) if (!allowed.contains(keys.next())) throw new IllegalArgumentException();
            if (!"gugugu.server".equals(value.get("type")) || !(value.get("version") instanceof Integer) || value.getInt("version") != 1) throw new IllegalArgumentException();
            if (!(value.get("url") instanceof String) || !(value.get("api_token") instanceof String) || !(value.get("name") instanceof String)) throw new IllegalArgumentException();
            String name = value.getString("name").trim(), url = value.getString("url").trim(), token = value.getString("api_token");
            if (name.isEmpty() || name.length() > 80 || url.length() > 2048 || token.length() < 32 || token.length() > 512 || !token.equals(token.trim())) throw new IllegalArgumentException();
            URI uri = new URI(url);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() > 65535 || uri.getPort() == 0) throw new IllegalArgumentException();
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            return new PairingConfig(name, url, token);
        } catch (Exception error) { throw new UserError(R.string.invalid_gugugu_pairing_code_scan_the_qr_code_in_the_service_admin); }
    }
}
