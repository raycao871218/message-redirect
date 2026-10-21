package com.nas.messageredirect;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.mail.internet.MimeMessage;

public final class SmtpTest {
    private SmtpProfile profile(String security, int port) {
        return new SmtpProfile("localhost", port, security, "sender@example.invalid", "fixture-secret", "sender@example.invalid", "recipient@example.invalid");
    }
    @Test public void requiresTlsAndRejectsRecipientInjection() {
        SmtpProfile tls = profile("starttls", 587); tls.validate();
        assertEquals("true", tls.properties().getProperty("mail.smtp.starttls.required"));
        assertEquals("true", tls.properties().getProperty("mail.smtp.ssl.checkserveridentity"));
        assertEquals("true", profile("ssl", 465).properties().getProperty("mail.smtp.ssl.enable"));
        for (String to : new String[]{"a@example.invalid,b@example.invalid", "a@example.invalid\r\nBcc: b@example.invalid", "Name <a@example.invalid>", "missing-domain"}) {
            assertThrows(IllegalArgumentException.class, () -> SmtpProfile.mailbox(to, "收件邮箱"));
        }
        assertThrows(IllegalArgumentException.class, () -> profile("none", 25).validate());
        assertThrows(IllegalArgumentException.class, () -> profile("ssl", 65536).validate());
        assertThrows(IllegalArgumentException.class, () -> new SmtpProfile("SMTP-MAIL.OUTLOOK.COM", 587, "starttls", "fixture", "fixture", "from@example.invalid", "to@example.invalid").validate());
    }
    @Test public void utf8ContentAndStableMessageId() throws Exception {
        SmsStore.Message sms = new SmsStore.Message("10086\r\nTest", "中文正文\n第二行", 123456, "实时接收", "fixture-id", 123457, false);
        MimeMessage first = SmtpSender.message(profile("ssl", 465), "device-a", sms);
        MimeMessage again = SmtpSender.message(profile("ssl", 465), "device-a", sms);
        assertEquals(first.getMessageID(), again.getMessageID());
        assertNotEquals(first.getMessageID(), SmtpSender.message(profile("ssl", 465), "device-b", sms).getMessageID());
        assertTrue(first.getContent().toString().contains(sms.body));
        assertFalse(first.getSubject().contains("\r"));
        assertEquals(1, first.getAllRecipients().length);
        assertNull(first.getHeader("Bcc"));
    }
    @Test public void missingStarttlsFailsBeforeCredentialsOrData() throws Exception {
        AtomicBoolean data = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(10000);
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    socket.getOutputStream().write("220 fixture\r\n".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
                    assertTrue(reader.readLine().startsWith("EHLO"));
                    socket.getOutputStream().write("250-fixture\r\n250 AUTH LOGIN PLAIN\r\n".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
                    String next = reader.readLine();
                    assertTrue(next == null || next.equals("QUIT"));
                } catch (Throwable error) { failure.set(error); }
            });
            responder.start();
            SmsStore.Message sms = new SmsStore.Message("10086", "fixture", 1, "test", "id", 2, false);
            assertThrows(Exception.class, () -> SmtpSender.send(profile("starttls", server.getLocalPort()), "device", sms, () -> data.set(true)));
            responder.join(12000); assertFalse(responder.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertFalse(data.get());
        }
    }
    @Test public void distinguishesReceivingPhoneFromSenderAndHandlesUnknownHistory() throws Exception {
        SmsStore.Message sms = new SmsStore.Message("10086", "fixture body", 1, "test", "id", 2, false, null, null, "pending", "+8613800000000");
        MimeMessage mail = SmtpSender.message(profile("ssl", 465), "fixture-device", sms);
        assertTrue(mail.getSubject().contains("+8613800000000 ← 10086"));
        assertTrue(mail.getContent().toString().contains("Receiving number: +8613800000000\nSender: 10086"));
        SmsStore.Message old = new SmsStore.Message("10086", "history", 1, "test", "old", 2, false);
        assertTrue(SmtpSender.message(profile("ssl", 465), "fixture-device", old).getContent().toString().contains("Receiving number: Not recorded"));
    }
}
