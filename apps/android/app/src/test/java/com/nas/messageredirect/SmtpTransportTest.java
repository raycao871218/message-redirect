package com.nas.messageredirect;

import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;

/** Isolated TLS fixture: credentials and message bodies are synthetic. No external mailbox. */
public final class SmtpTransportTest {
    private static SSLContext previous, fixture;
    private static Path directory;
    @BeforeClass public static void createTlsFixture() throws Exception {
        directory = Files.createTempDirectory("smtp-fixture");
        Path keys = directory.resolve("fixture.p12");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", keys.toString(),
            "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt").redirectErrorStream(true).start();
        process.getInputStream().readAllBytes(); assertEquals(0, process.waitFor());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(keys)) { store.load(input, "fixture-only".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(store, "fixture-only".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(store);
        fixture = SSLContext.getInstance("TLS"); fixture.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        previous = SSLContext.getDefault(); SSLContext.setDefault(fixture);
    }
    @AfterClass public static void cleanup() throws Exception {
        SSLContext.setDefault(previous);
        Files.deleteIfExists(directory.resolve("fixture.p12")); Files.deleteIfExists(directory);
    }
    @Test public void acceptedDataSurvivesQuitDisconnect() throws Exception { runFixture(false, false); }
    @Test public void lostDataAcknowledgementIsAmbiguous() throws Exception { runFixture(true, false); }
    @Test public void certificateHostMismatchStopsBeforeData() throws Exception { runFixture(false, true); }

    private void runFixture(boolean dropAcknowledgement, boolean mismatch) throws Exception {
        AtomicBoolean dataStarted = new AtomicBoolean(), received = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (SSLServerSocket server = (SSLServerSocket) fixture.getServerSocketFactory().createServerSocket(0)) {
            server.setSoTimeout(10000);
            Thread responder = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.setSoTimeout(10000); socket.startHandshake();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    OutputStream output = socket.getOutputStream();
                    reply(output, "220 fixture");
                    String line = reader.readLine();
                    if (mismatch) { assertNull(line); return; }
                    assertTrue(line.startsWith("EHLO")); reply(output, "250-fixture\r\n250 AUTH PLAIN");
                    line = reader.readLine(); assertTrue(line.startsWith("AUTH PLAIN"));
                    if (line.equals("AUTH PLAIN")) { reply(output, "334 "); line = reader.readLine(); }
                    reply(output, "235 Authenticated");
                    assertTrue(reader.readLine().startsWith("MAIL FROM:")); reply(output, "250 Sender OK");
                    assertTrue(reader.readLine().startsWith("RCPT TO:")); reply(output, "250 Recipient OK");
                    assertEquals("DATA", reader.readLine()); reply(output, "354 Send data");
                    StringBuilder content = new StringBuilder();
                    while (!(line = reader.readLine()).equals(".")) content.append(line).append("\r\n");
                    jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()), new ByteArrayInputStream(content.toString().getBytes(StandardCharsets.US_ASCII)));
                    assertTrue(message.getContent().toString().contains("中文测试短信")); received.set(true);
                    if (!dropAcknowledgement) reply(output, "250 Accepted");
                    // Simulate connection loss before QUIT (or before DATA acknowledgement).
                } catch (Throwable error) {
                    if (!mismatch) failure.set(error);
                }
            });
            responder.start();
            SmtpProfile profile = new SmtpProfile(mismatch ? "127.0.0.1" : "localhost", server.getLocalPort(), "ssl", "fixture", "fixture-only", "from@example.invalid", "to@example.invalid");
            SmsStore.Message sms = new SmsStore.Message("10086", "中文测试短信", 1, "test", "fixture-id", 2, false);
            try {
                SmtpSender.send(profile, "fixture-device", sms, () -> dataStarted.set(true));
                assertFalse("expected exception", dropAcknowledgement || mismatch);
            } catch (Exception error) { assertTrue("unexpected exception: " + error, dropAcknowledgement || mismatch); }
            responder.join(12000); assertFalse(responder.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(!mismatch, dataStarted.get()); assertEquals(!mismatch, received.get());
        }
    }
    private static void reply(OutputStream output, String text) throws IOException {
        output.write((text + "\r\n").getBytes(StandardCharsets.US_ASCII)); output.flush();
    }
}
