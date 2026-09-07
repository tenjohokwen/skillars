package com.softropic.skillars.platform.notification.health;

import com.softropic.skillars.platform.notification.contract.EmailProperties;
import com.softropic.skillars.platform.notification.contract.ProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "email", name = "providerConfigs[0].host")
public class SmtpHealthIndicator extends AbstractHealthIndicator {

	private static final int SOCKET_TIMEOUT_MS = 5000;
	private static final int CONNECT_TIMEOUT_MS = 5000;

	private final EmailProperties emailProperties;

	public SmtpHealthIndicator(EmailProperties emailProperties) {
		this.emailProperties = emailProperties;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) {
		List<ProviderConfig> configs = emailProperties.getProviderConfigs();

		if (configs == null || configs.isEmpty()) {
			builder.status("UNKNOWN")
				.withDetail("reason", "No email providers configured");
			return;
		}

		List<ProviderStatus> statuses = new ArrayList<>();

		for (ProviderConfig config : configs) {
			ProviderStatus status = checkProvider(config);
			statuses.add(status);
		}

		// Rollup semantics: all-down = DOWN, any-up = UP, all-unknown = UNKNOWN
		boolean hasUp = statuses.stream().anyMatch(s -> "UP".equals(s.status));
		boolean hasDown = statuses.stream().anyMatch(s -> "DOWN".equals(s.status));

		if (hasUp) {
			builder.status("UP")
				.withDetail("providers", statuses);
		} else if (hasDown) {
			builder.status("DOWN")
				.withDetail("providers", statuses);
		} else {
			builder.status("UNKNOWN")
				.withDetail("providers", statuses);
		}
	}

	private ProviderStatus checkProvider(ProviderConfig config) {
		String host = config.getHost();
		String portStr = config.getPort();

		if (host == null || host.isBlank() || portStr == null || portStr.isBlank()) {
			return new ProviderStatus(config.getName(), "UNKNOWN", "Host or port not configured");
		}

		try {
			int port = Integer.parseInt(portStr);
			if (port < 0 || port > 65535) {
				return new ProviderStatus(config.getName(), "UNKNOWN", "Port out of range: " + portStr);
			}
			if (probeSmtpConnection(host, port)) {
				return new ProviderStatus(config.getName(), "UP", "SMTP EHLO successful");
			} else {
				return new ProviderStatus(config.getName(), "DOWN", "SMTP EHLO failed");
			}
		} catch (NumberFormatException e) {
			return new ProviderStatus(config.getName(), "UNKNOWN", "Invalid port number: " + portStr);
		} catch (Exception e) {
			String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			return new ProviderStatus(config.getName(), "DOWN", detail);
		}
	}

	/**
	 * Opens a TCP connection to {@code host:port}, reads the SMTP {@code 220} banner and performs
	 * an {@code EHLO} handshake, returning {@code true} only when the server answers {@code 250}.
	 *
	 * <p>Package-private and non-{@code private} on purpose: it is the single network seam of this
	 * indicator, so unit tests override it to exercise the UP / DOWN / error rollup without opening
	 * a socket. Nothing else should call it.
	 */
	boolean probeSmtpConnection(String host, int port) throws IOException {
		try (Socket socket = new Socket()) {
			socket.setTcpNoDelay(true);
			socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
			socket.setSoTimeout(SOCKET_TIMEOUT_MS);

			try (var in = socket.getInputStream();
				 var out = socket.getOutputStream()) {

				// Read SMTP banner (220 response) — read until newline to handle fragmentation
				String banner = readSmtpLine(in);
				if (banner == null || !banner.startsWith("220")) {
					log.warn("SMTP banner from {}:{} does not start with 220: {}", host, port, banner);
					return false;
				}
				log.debug("SMTP banner from {}:{}: {}", host, port, banner);

				// Send EHLO and verify 250 response. A failure to resolve our own hostname must
				// not masquerade as an SMTP outage, so fall back to a literal.
				String hostname;
				try {
					hostname = java.net.InetAddress.getLocalHost().getHostName();
				} catch (IOException e) {
					hostname = "localhost";
				}
				out.write(("EHLO " + hostname + "\r\n").getBytes());
				out.flush();

				String response = readSmtpLine(in);
				if (response == null || !response.startsWith("250")) {
					log.warn("SMTP EHLO from {}:{} did not return 250: {}", host, port, response);
					return false;
				}
				log.debug("SMTP EHLO response from {}:{}: {}", host, port, response);

				return true;
			}
		} catch (SocketTimeoutException e) {
			log.warn("SMTP connection to {}:{} timed out", host, port, e);
			return false;
		} catch (IOException e) {
			log.warn("SMTP connection to {}:{} failed: {}", host, port, e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Reads one CRLF-terminated SMTP reply line, one byte at a time so a banner split across TCP
	 * segments is still assembled correctly. Package-private so it can be unit-tested directly.
	 * Returns {@code null} at end of stream with nothing buffered.
	 */
	static String readSmtpLine(java.io.InputStream in) throws IOException {
		StringBuilder line = new StringBuilder();
		int c;
		while ((c = in.read()) != -1) {
			if (c == '\n') {
				break;
			}
			if (c != '\r') {
				line.append((char) c);
			}
		}
		return line.length() > 0 ? line.toString() : null;
	}

	/** Package-private so unit tests can assert on the serialized per-provider fields. */
	static class ProviderStatus {
		public final String name;
		public final String status;
		public final String detail;

		ProviderStatus(String name, String status, String detail) {
			this.name = name != null ? name : "unknown";
			this.status = status;
			this.detail = detail != null ? detail : "Unknown error";
		}
	}
}
