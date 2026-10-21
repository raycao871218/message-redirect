package com.nas.messageredirect;

import jakarta.mail.internet.InternetAddress;
import java.util.Properties;

/** Immutable configuration. Passwords and addresses must never be logged. */
final class SmtpProfile {
    final String host, security, username, password, from, to;
    final int port;
    SmtpProfile(String host, int port, String security, String username, String password, String from, String to) {
        this.host = host.trim(); this.port = port; this.security = security;
        this.username = username.trim(); this.password = password;
        this.from = from.trim(); this.to = to.trim();
    }
    void validate() {
        if (host.isEmpty() || host.length() > 253 || !host.matches("[A-Za-z0-9.-]+") || host.startsWith(".") || host.endsWith("."))
            throw new UserError(R.string.enter_only_the_smtp_hostname_or_ip_without_a_protocol_or_port);
        if (port < 1 || port > 65535) throw new UserError(R.string.smtp_port_must_be_between_1_and_65535);
        if (!"ssl".equals(security) && !"starttls".equals(security)) throw new UserError(R.string.choose_ssl_tls_or_starttls);
        SmtpPresets.requirePasswordAuth(host);
        if (username.isEmpty() || username.contains("\r") || username.contains("\n") || password.isEmpty())
            throw new UserError(R.string.enter_your_smtp_account_and_password_or_authorization_code);
        mailbox(from, "sender"); mailbox(to, "recipient");
    }
    static InternetAddress mailbox(String value, String label) {
        try {
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('<') >= 0 || value.indexOf('>') >= 0)
                throw new IllegalArgumentException();
            InternetAddress[] addresses = InternetAddress.parse(value, true);
            if (addresses.length != 1 || addresses[0].isGroup() || !value.equals(addresses[0].getAddress()) || !value.contains("@"))
                throw new IllegalArgumentException();
            addresses[0].validate(); return addresses[0];
        } catch (Exception error) { throw new UserError(R.string.enter_a_single_valid_email_address); }
    }
    Properties properties() {
        Properties props = new Properties();
        props.setProperty("mail.smtp.auth", "true");
        props.setProperty("mail.smtp.auth.mechanisms", "LOGIN PLAIN");
        props.setProperty("mail.smtp.ssl.enable", String.valueOf("ssl".equals(security)));
        props.setProperty("mail.smtp.starttls.enable", String.valueOf("starttls".equals(security)));
        props.setProperty("mail.smtp.starttls.required", String.valueOf("starttls".equals(security)));
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        props.setProperty("mail.smtp.ssl.protocols", "TLSv1.2");
        props.setProperty("mail.smtp.connectiontimeout", "8000");
        props.setProperty("mail.smtp.timeout", "15000");
        props.setProperty("mail.smtp.writetimeout", "15000");
        // A server may accept DATA but disconnect during QUIT; do not retry a confirmed message for that reason.
        props.setProperty("mail.smtp.quitwait", "false");
        props.setProperty("mail.debug", "false");
        return props;
    }
}
