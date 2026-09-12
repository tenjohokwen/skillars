package com.softropic.skillars.infrastructure.email.smtp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal, single-connection, in-JVM SMTP server stub — just enough of the protocol
 * (banner/EHLO/AUTH/MAIL FROM/RCPT TO/DATA/QUIT) for a real {@code JavaMailSenderImpl.send(...)}
 * call to complete successfully against it, so a test can prove a real socket round-trip happened
 * rather than only that beans wired.
 *
 * <p>Hand-rolled rather than a library dependency (no GreenMail/SubEthaSMTP in this project's
 * {@code pom.xml}) — matching this codebase's established preference for a narrow, dependency-free
 * test double over a new dependency for one test's need (same reasoning as {@code
 * NoStraySmtpConfigTest}/{@code EmailTransportArchitectureTest} avoiding ArchUnit).
 *
 * <p>Does not advertise {@code STARTTLS} in its {@code EHLO} response, so {@code
 * mail.smtp.starttls.enable=true} (opportunistic, not required — see {@code MailSenderProvider})
 * never triggers a TLS handshake this stub doesn't implement.
 */
final class FakeSmtpServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final List<String> transcript = new CopyOnWriteArrayList<>();
    private volatile String lastMessageBody;

    FakeSmtpServer() throws IOException {
        this.serverSocket = new ServerSocket(0);
        this.acceptThread = new Thread(this::acceptOneConnection, "fake-smtp-server");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /** Every line the client sent, in order — including the command verb lines. */
    List<String> transcript() {
        return transcript;
    }

    /** The raw body of the most recently accepted {@code DATA} block, or {@code null} if none yet. */
    String lastMessageBody() {
        return lastMessageBody;
    }

    private void acceptOneConnection() {
        try (Socket socket = serverSocket.accept();
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream out = socket.getOutputStream()) {

            reply(out, "220 fake-smtp.test ready");
            String line;
            while ((line = in.readLine()) != null) {
                transcript.add(line);
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                    reply(out, "250-fake-smtp.test greets you", "250 AUTH PLAIN LOGIN");
                } else if (upper.startsWith("AUTH")) {
                    reply(out, "235 2.7.0 Authentication successful");
                } else if (upper.startsWith("MAIL FROM")) {
                    reply(out, "250 OK");
                } else if (upper.startsWith("RCPT TO")) {
                    reply(out, "250 OK");
                } else if (upper.startsWith("DATA")) {
                    reply(out, "354 Start mail input; end with <CRLF>.<CRLF>");
                    lastMessageBody = readDataBlock(in);
                    reply(out, "250 OK: queued as fake-1");
                } else if (upper.startsWith("QUIT")) {
                    reply(out, "221 Bye");
                    return;
                } else {
                    reply(out, "500 unrecognised command (fake-smtp-server stub)");
                }
            }
        } catch (IOException ignored) {
            // Connection closed/reset — nothing further to do for a single-shot test stub.
        }
    }

    private static String readDataBlock(BufferedReader in) throws IOException {
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals(".")) {
                break;
            }
            body.append(line).append("\r\n");
        }
        return body.toString();
    }

    private static void reply(OutputStream out, String... lines) throws IOException {
        for (String line : lines) {
            out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        out.flush();
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
