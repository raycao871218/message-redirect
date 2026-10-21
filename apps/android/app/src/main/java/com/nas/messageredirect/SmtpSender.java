package com.nas.messageredirect;

import android.content.Context;
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
        return message(null, profile, deviceId, sms);
    }
    static MimeMessage message(Context context, SmtpProfile profile, String deviceId, SmsStore.Message sms) throws Exception {
        Session session = Session.getInstance(profile.properties()); session.setDebug(false);
        // Reuse the same Message-ID for retries, though SMTP does not guarantee recipient-side deduplication.
        String identity = UUID.nameUUIDFromBytes((deviceId + ":" + sms.id).getBytes(StandardCharsets.UTF_8)).toString();
        MimeMessage message = new MimeMessage(session) {
            @Override protected void updateMessageID() throws MessagingException { setHeader("Message-ID", "<sms-" + identity + "@message-redirect.invalid>"); }
        };
        message.setFrom(SmtpProfile.mailbox(profile.from, "sender"));
        message.setRecipient(Message.RecipientType.TO, SmtpProfile.mailbox(profile.to, "recipient"));
        String sender = sms.sender.replace('\r', ' ').replace('\n', ' ');
        String receiving = sms.receivingNumber.isEmpty() ? (context == null ? "Not recorded" : context.getString(R.string.not_recorded)) : sms.receivingNumber.replace('\r', ' ').replace('\n', ' ');
        String subject = context == null ? "SMS forwarding · %1$s ← %2$s" : context.getString(R.string.email_subject);
        message.setSubject(String.format(subject, receiving, sender), "UTF-8");
        message.setSentDate(new Date(sms.capturedAt));
        DateFormat dates = context == null ? DateFormat.getDateTimeInstance() : DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, context.getResources().getConfiguration().locale);
        String body = context == null ? "Receiving number: %1$s\nSender: %2$s\nSMS time: %3$s\nReceived at: %4$s\nSource: %5$s\nRecord ID: %6$s\n\n%7$s" : context.getString(R.string.email_body);
        String source = sms.source;
        if (context != null && "实时接收".equals(source)) source = context.getString(R.string.live_sms);
        message.setText(String.format(body, receiving, sms.sender, dates.format(new Date(sms.time)),
            dates.format(new Date(sms.capturedAt)), source, sms.id, sms.body), "UTF-8");
        message.saveChanges(); return message;
    }
    static void send(SmtpProfile profile, String deviceId, SmsStore.Message sms, BeforeData beforeData) throws Exception {
        send(null, profile, deviceId, sms, beforeData);
    }
    static void send(Context context, SmtpProfile profile, String deviceId, SmsStore.Message sms, BeforeData beforeData) throws Exception {
        profile.validate();
        Session session = Session.getInstance(profile.properties()); session.setDebug(false);
        MimeMessage message = message(context, profile, deviceId, sms);
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
