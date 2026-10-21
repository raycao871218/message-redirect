package com.nas.messageredirect;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;

/** Persist status identity and arguments, never a translation tied to yesterday's language. */
final class StatusText {
    private StatusText() {}
    static void save(Context context, SharedPreferences preferences, int resource, Object... args) {
        JSONArray values = new JSONArray();
        for (Object arg : args) values.put(arg);
        preferences.edit().putString("status_key", context.getResources().getResourceEntryName(resource))
            .putString("status_args", values.toString()).putLong("status_at", System.currentTimeMillis())
            .remove("status").apply();
    }
    static String read(Context context, SharedPreferences preferences) {
        String key = preferences.getString("status_key", "");
        if (!key.isEmpty()) {
            int resource = resource(context, key);
            if (resource != 0) try {
                JSONArray values = new JSONArray(preferences.getString("status_args", "[]"));
                Object[] args = new Object[values.length()];
                for (int i = 0; i < args.length; i++) args[i] = values.get(i);
                String message = context.getString(resource, args);
                long time = preferences.getLong("status_at", 0);
                return time == 0 ? message : message + " · " + DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, context.getResources().getConfiguration().locale).format(new Date(time));
            } catch (Exception ignored) { /* Old/invalid status metadata must not affect the queue. */ }
        }
        return legacy(context, preferences.getString("status", ""));
    }
    private static int resource(Context context, String key) {
        for (int id : LEGACY) if (context.getResources().getResourceEntryName(id).equals(key)) return id;
        for (int id : new int[]{R.string.status_mail_accepted, R.string.status_archived, R.string.status_http_error})
            if (context.getResources().getResourceEntryName(id).equals(key)) return id;
        return 0;
    }
    private static String legacy(Context context, String value) {
        Configuration chinese = new Configuration(context.getResources().getConfiguration());
        chinese.setLocale(Locale.SIMPLIFIED_CHINESE);
        Context oldLanguage = context.createConfigurationContext(chinese);
        // Earlier versions persisted Chinese UI text. Translate known states without touching app data.
        for (int resource : LEGACY) {
            if (value.equals(oldLanguage.getString(resource))) return context.getString(resource);
        }
        if (value.startsWith(oldLanguage.getString(R.string.email_delivery_needs_confirmation)))
            return context.getString(R.string.some_emails_have_an_uncertain_delivery_status_go_to_more_actions);
        java.util.regex.Matcher mail = java.util.regex.Pattern.compile("^发件服务器已接收(?:本次)? (\\d+) 封邮件.*").matcher(value);
        if (mail.matches()) return context.getString(R.string.status_mail_accepted, Integer.parseInt(mail.group(1)));
        java.util.regex.Matcher archived = java.util.regex.Pattern.compile("^本次已保存 (\\d+) 条短信到数据库.*").matcher(value);
        if (archived.matches()) return context.getString(R.string.status_archived, Integer.parseInt(archived.group(1)));
        java.util.regex.Matcher http = java.util.regex.Pattern.compile("^保存到数据库未成功（HTTP (\\d+)）.*").matcher(value);
        if (http.matches()) return context.getString(R.string.status_http_error, Integer.parseInt(http.group(1)));
        return context.getString(R.string.new_sms_will_be_forwarded_automatically_unsent_messages_will_be_r);
    }
    private static final int[] LEGACY = {
        R.string.new_sms_will_be_forwarded_automatically,
        R.string.new_sms_will_be_forwarded_automatically_unsent_messages_will_be_r,
        R.string.some_emails_have_an_uncertain_delivery_status_go_to_more_actions,
        R.string.profile_switched_new_sms_will_be_forwarded_automatically_and_unse,
        R.string.the_connection_dropped_while_sending_the_email_may_already_have_b,
        R.string.could_not_connect_or_sign_in_to_your_email_provider_sms_is_kept_f,
        R.string.forwarding_was_not_completed_sms_is_kept_on_the_phone_for_retry_c,
        R.string.resend_scheduled_keep_forwarding_enabled_and_select_the_original,
        R.string.marked_as_received_this_email_will_not_be_sent_again,
        R.string.could_not_connect_or_sign_in_to_your_email_provider_check_your_ne
    };
}
