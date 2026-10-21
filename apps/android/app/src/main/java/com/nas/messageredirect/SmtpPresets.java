package com.nas.messageredirect;

final class SmtpPresets {
    static final String MICROSOFT_HELP = "https://support.microsoft.com/outlook/pop-imap-and-smtp-settings-for-outlook-com";
    static final int CUSTOM_HINT = R.string.for_a_custom_provider_enter_its_smtp_server_port_and_app_password;
    static final class Preset {
        final String host, security, help;
        final int name, hint;
        final int port;
        Preset(int name, String host, int port, String security, int hint, String help) {
            this.name = name; this.host = host; this.port = port; this.security = security; this.hint = hint; this.help = help;
        }
    }
    static final Preset[] ALL = {
        new Preset(R.string.custom_keep_current_settings, "", 465, "ssl", CUSTOM_HINT, ""),
        new Preset(R.string.provider_gmail, "smtp.gmail.com", 465, "ssl",
            R.string.enable_2_step_verification_in_your_google_account_then_generate_a,
            "https://support.google.com/accounts/answer/185833"),
        new Preset(R.string.qq_mail, "smtp.qq.com", 465, "ssl",
            R.string.enable_smtp_in_qq_mail_settings_generate_an_authorization_code_an,
            "https://service.mail.qq.com/"),
        new Preset(R.string.microsoft_outlook_hotmail_oauth2_required, "smtp-mail.outlook.com", 587, "starttls",
            R.string.personal_microsoft_accounts_require_oauth2_this_version_does_not,
            MICROSOFT_HELP),
        new Preset(R.string.netease_163_mail, "smtp.163.com", 465, "ssl",
            R.string.enable_smtp_in_netease_mail_settings_and_obtain_a_client_authoriz,
            "https://help.mail.126.com/faq.do?categoryID=90&m=list"),
        new Preset(R.string.netease_126_mail, "smtp.126.com", 465, "ssl",
            R.string.enable_smtp_in_netease_mail_settings_and_obtain_a_client_authoriz,
            "https://help.mail.126.com/faq.do?categoryID=90&m=list")
    };
    static Preset matching(String host) {
        for (Preset preset : ALL) if (!preset.host.isEmpty() && preset.host.equalsIgnoreCase(host.trim())) return preset;
        return ALL[0];
    }
    static boolean requiresOAuth(String host) { return "smtp-mail.outlook.com".equalsIgnoreCase(host.trim()); }
    static void requirePasswordAuth(String host) {
        if (requiresOAuth(host)) throw new UserError(R.string.personal_microsoft_accounts_require_oauth2_which_is_not_supported);
    }
}
