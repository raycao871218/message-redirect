package com.nas.messageredirect;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.Locale;
import jakarta.mail.internet.MimeMessage;

/** Exercises actual Android resource selection, including language changes after persisting status. */
final class LocaleChecks {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static Context language(Context base, String tag) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (android.os.Build.VERSION.SDK_INT >= 24) config.setLocales(LocaleList.forLanguageTags(tag));
        else config.setLocale(Locale.forLanguageTag(tag));
        return base.createConfigurationContext(config);
    }
    static void run(Context base) throws Exception {
        Context english = language(base, "en-US"), chinese = language(base, "zh-CN"), japanese = language(base, "ja-JP");
        check(english.getString(R.string.next).equals("Next"), "English resources");
        check(chinese.getString(R.string.next).equals("下一步"), "Chinese resources");
        check(japanese.getString(R.string.next).equals("次へ"), "Japanese resources");
        check(language(base, "zh-TW").getString(R.string.next).equals("下一步"), "Chinese regional fallback");
        check(language(base, "en-GB").getString(R.string.next).equals("Next"), "English regional fallback");
        check(language(base, "fr-FR").getString(R.string.next).equals("Next"), "Unsupported language uses English");
        if (android.os.Build.VERSION.SDK_INT >= 24)
            check(language(base, "ja-JP,en-US").getString(R.string.next).equals("次へ"), "System language priority");
        check(english.getResources().getQuantityString(R.plurals.queue_sending, 1, 1).equals("Sending: 1 email"), "Singular email count");
        check(english.getResources().getQuantityString(R.plurals.queue_sending, 2, 2).equals("Sending: 2 emails"), "Plural email count");
        SharedPreferences prefs = base.getSharedPreferences("test-localization", Context.MODE_PRIVATE);
        try {
            prefs.edit().clear().commit();
            StatusText.save(english, prefs, R.string.status_archived, 7);
            check(StatusText.read(chinese, prefs).startsWith("本次已保存 7 条"), "Saved English status re-renders in Chinese");
            check(StatusText.read(japanese, prefs).startsWith("SMSを7件"), "Saved English status re-renders in Japanese");
            prefs.edit().remove("status_key").putString("status", "发件服务器已接收本次 3 封邮件 · 旧日期").commit();
            check(StatusText.read(english, prefs).startsWith("Emails accepted by server: 3"), "Legacy Chinese count translated");
            prefs.edit().putString("status", "已切换配置。收到新短信后自动转发，尚未发送的短信会继续尝试。").commit();
            check(StatusText.read(japanese, prefs).startsWith("設定を切り替えました"), "Legacy profile status translated");
            UserError error = new UserError(R.string.smtp_port_must_be_between_1_and_65535);
            check(UserError.message(english, error).startsWith("SMTP port"), "English validation");
            check(UserError.message(japanese, error).startsWith("SMTPポート"), "Japanese validation");
            check(UserError.message(english, new IllegalArgumentException("fixture-secret")).equals(english.getString(R.string.unexpected_error)), "Unknown errors do not reveal secrets");
            SmtpProfile profile = new SmtpProfile("localhost", 465, "ssl", "sender@example.invalid", "fixture-secret", "sender@example.invalid", "recipient@example.invalid");
            SmsStore.Message sms = new SmsStore.Message("10086", "SMS 100% 中文 日本語", 1, "实时接收", "locale-fixture", 2, false);
            for (Context context : new Context[]{english, chinese, japanese}) {
                MimeMessage mail = SmtpSender.message(context, profile, "fixture", sms);
                check(mail.getContent().toString().contains(sms.body), "Original SMS text preserved");
                check(mail.getContent().toString().contains(context.getString(R.string.live_sms)), "Source label localized");
                check(mail.getSubject().contains(context.getString(R.string.not_recorded)), "Missing receiving number localized");
                check(!context.getString(SmtpPresets.matching("smtp.qq.com").hint).isEmpty(), "Provider guidance localized");
            }
        } finally { prefs.edit().clear().commit(); }
    }
}
