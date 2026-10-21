package com.nas.messageredirect;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.util.UUID;

final class SyncConfig {
    static final String DATABASE = "database", EMAIL = "email";
    final SharedPreferences prefs;
    final Context context;
    SyncConfig(Context context) { this(context, "sync"); }
    SyncConfig(Context context, String name) { this.context = AppLanguage.wrap(context); prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE); }
    String url() { return prefs.getString("url", ""); }
    String token() { return prefs.getString("token", ""); }
    String mode() { return EMAIL.equals(prefs.getString("delivery_mode", DATABASE)) ? EMAIL : DATABASE; }
    String modeLabel() { return EMAIL.equals(mode()) ? context.getString(R.string.email_forwarding) : context.getString(R.string.self_hosted_archive); }
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
        if (first.isEmpty() && second.isEmpty()) throw new UserError(R.string.enter_the_phone_number_that_receives_sms);
        if (!first.isEmpty() && first.equals(second)) throw new UserError(R.string.enter_different_numbers_for_the_two_sims);
        saveInternal(address, token, enabled, mode, smtp, first, second);
    }
    static String normalizePhone(String phone) {
        String clean = phone.trim().replace(" ", "").replace("-", "").replace("(", "").replace(")", "");
        if (!clean.isEmpty() && !clean.matches("\\+?[0-9]{5,20}")) throw new UserError(R.string.phone_numbers_need_5_20_digits_optionally_starting_with);
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
            throw new UserError(R.string.smtp_server_or_account_changed_enter_the_password_or_authorizatio);
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
            if (!DeliveryLock.LOCK.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS)) throw new UserError(R.string.sms_is_being_sent_change_settings_later);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new UserError(R.string.saving_interrupted_try_again);
        }
        try {
            if (!DATABASE.equals(mode) && !EMAIL.equals(mode)) throw new UserError(R.string.choose_a_forwarding_method);
            address = address.trim(); token = token.trim();
            if (DATABASE.equals(mode)) {
                URI uri;
                try { uri = new URI(address); } catch (Exception e) { throw new UserError(R.string.invalid_api_url); }
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                    throw new UserError(R.string.enter_a_full_http_https_service_url);
                if (enabled && token.length() < 32) throw new UserError(R.string.enter_the_server_access_token_at_least_32_characters);
            }
            while (address.endsWith("/")) address = address.substring(0, address.length() - 1);
            if (EMAIL.equals(mode) && enabled) { if (smtp == null) throw new UserError(R.string.enter_smtp_settings); smtp.validate(); }
            android.content.SharedPreferences.Editor editor = prefs.edit().putString("url", address).putString("token", token)
                .putBoolean("enabled", enabled).putString("delivery_mode", mode);
            if (smtp != null) editor.putString("smtp_host", smtp.host).putInt("smtp_port", smtp.port)
                .putString("smtp_security", smtp.security).putString("smtp_username", smtp.username)
                .putString("smtp_password_encrypted", SecretStore.encrypt(smtp.password))
                .putString("smtp_from", smtp.from).putString("smtp_to", smtp.to);
            if (first != null) editor.putString("receiving_phone_1", first).putString("receiving_phone_2", second).putBoolean("setup_complete", true);
            if (!editor.commit()) throw new UserError(R.string.could_not_save_settings_try_again);
        } finally { DeliveryLock.LOCK.unlock(); }
    }
    String status() { return StatusText.read(context, prefs); }
    void status(int resource, Object... args) { StatusText.save(context, prefs, resource, args); }
    void status(String value) { prefs.edit().remove("status_key").putString("status", value).apply(); }
}
