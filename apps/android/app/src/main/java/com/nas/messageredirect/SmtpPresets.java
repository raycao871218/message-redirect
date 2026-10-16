package com.nas.messageredirect;

final class SmtpPresets {
    static final String MICROSOFT_HELP = "https://support.microsoft.com/zh-cn/outlook/pop-imap-and-smtp-settings-for-outlook-com";
    static final String CUSTOM_HINT = "自定义邮箱需填写服务商提供的 SMTP 服务器、端口和授权码。";
    static final class Preset {
        final String name, host, security, hint, help;
        final int port;
        Preset(String name, String host, int port, String security, String hint, String help) {
            this.name = name; this.host = host; this.port = port; this.security = security; this.hint = hint; this.help = help;
        }
    }
    static final Preset[] ALL = {
        new Preset("自定义／保留当前参数", "", 465, "ssl", CUSTOM_HINT, ""),
        new Preset("Google Gmail", "smtp.gmail.com", 465, "ssl",
            "先开启 Google 账号两步验证，再生成应用专用密码，填入下方授权码栏。",
            "https://support.google.com/accounts/answer/185833?hl=zh-Hans"),
        new Preset("QQ 邮箱", "smtp.qq.com", 465, "ssl",
            "在 QQ 邮箱设置中开启 SMTP 服务，生成授权码后填入下方。",
            "https://service.mail.qq.com/"),
        new Preset("微软 Outlook／Hotmail（需 OAuth2）", "smtp-mail.outlook.com", 587, "starttls",
            "微软个人邮箱要求 OAuth2 登录。本版尚未接入微软登录，仅提供服务器参数，暂不能用密码或授权码直接发送。",
            MICROSOFT_HELP),
        new Preset("网易 163 邮箱", "smtp.163.com", 465, "ssl",
            "在网易邮箱设置中开启 SMTP 服务，并获取客户端授权码。",
            "https://help.mail.126.com/faq.do?categoryID=90&m=list"),
        new Preset("网易 126 邮箱", "smtp.126.com", 465, "ssl",
            "在网易邮箱设置中开启 SMTP 服务，并获取客户端授权码。",
            "https://help.mail.126.com/faq.do?categoryID=90&m=list")
    };
    static Preset matching(String host) {
        for (Preset preset : ALL) if (!preset.host.isEmpty() && preset.host.equalsIgnoreCase(host.trim())) return preset;
        return ALL[0];
    }
    static boolean requiresOAuth(String host) { return "smtp-mail.outlook.com".equalsIgnoreCase(host.trim()); }
    static void requirePasswordAuth(String host) {
        if (requiresOAuth(host)) throw new IllegalArgumentException("微软个人邮箱需要 OAuth2 登录，本版暂不支持；请选择其他邮箱或暂时关闭发送");
    }
}
