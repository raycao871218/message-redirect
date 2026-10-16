package com.nas.messageredirect;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.UUID;

final class SmtpSender {
    interface BeforeData { void run(); }
    static MimeMessage message(SmtpProfile profile, String deviceId, SmsStore.Message sms) throws Exception {
        Session session = Session.getInstance(profile.properties()); session.setDebug(false);
        // Reuse the same Message-ID for retries, though SMTP does not guarantee recipient-side deduplication.
        String identity = UUID.nameUUIDFromBytes((deviceId + ":" + sms.id).getBytes(StandardCharsets.UTF_8)).toString();
        MimeMessage message = new MimeMessage(session) {
            @Override protected void updateMessageID() throws MessagingException { setHeader("Message-ID", "<sms-" + identity + "@message-redirect.invalid>"); }
        };
        message.setFrom(SmtpProfile.mailbox(profile.from, "发件邮箱"));
        message.setRecipient(Message.RecipientType.TO, SmtpProfile.mailbox(profile.to, "收件邮箱"));
        String sender = sms.sender.replace('\r', ' ').replace('\n', ' ');
        String receiving = sms.receivingNumber.isEmpty() ? "未记录" : sms.receivingNumber.replace('\r', ' ').replace('\n', ' ');
        message.setSubject("短信转发 · " + receiving + " ← " + sender, "UTF-8");
        message.setSentDate(new Date(sms.capturedAt));
        DateFormat dates = DateFormat.getDateTimeInstance();
        message.setText("接收手机号：" + receiving + "\n发送号码：" + sms.sender + "\n短信时间：" + dates.format(new Date(sms.time)) +
            "\n接收时间：" + dates.format(new Date(sms.capturedAt)) + "\n来源：" + sms.source +
            "\n记录 ID：" + sms.id + "\n\n" + sms.body, "UTF-8");
        message.saveChanges(); return message;
    }
    static void send(SmtpProfile profile, String deviceId, SmsStore.Message sms, BeforeData beforeData) throws Exception {
        profile.validate();
        Session session = Session.getInstance(profile.properties()); session.setDebug(false);
        MimeMessage message = message(profile, deviceId, sms);
        Transport transport = session.getTransport("smtp");
        try {
            transport.connect(profile.host, profile.port, profile.username, profile.password);
            // Connect/TLS/auth failures are safe to retry. From DATA onward the result can be ambiguous.
            beforeData.run();
            transport.sendMessage(message, message.getAllRecipients());
        } finally {
            try { transport.close(); } catch (MessagingException ignored) { /* DATA acknowledgement decides delivery. */ }
        }
    }
}
