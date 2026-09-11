package com.softropic.skillars.infrastructure.email.smtp;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Actuator health contributor for outbound SMTP reachability.
 *
 * <h2>skillars-deferred-99 AC5</h2>
 *
 * <ol>
 *   <li><strong>Parallel + bounded.</strong> Per-provider probes run on a small bounded pool under
 *       one overall wall-clock deadline ({@code app.email.smtp.health.overall-timeout},
 *       default {@code max(connect + read timeout) + 1s slack}), so N providers cost ≈ one timeout,
 *       not N × timeout.</li>
 *   <li><strong>Short-TTL cache.</strong> The aggregate {@link Health} is recomputed at most once per
 *       {@code app.email.smtp.health.ttl} (default 60s); every scrape in between is served
 *       from an {@link AtomicReference} without opening a socket.</li>
 *   <li><strong>Implicit-TLS aware.</strong> A provider on port 465 (or {@code implicit-tls: true})
 *       is probed with a TLS handshake, not a plaintext {@code 220} banner read — which such an
 *       endpoint never sends, so the old probe always reported it DOWN.</li>
 * </ol>
 *
 * <p><strong>TLS trust:</strong> the implicit-TLS probe uses the JVM's default
 * {@link SSLSocketFactory} — i.e. the default trust store — so a bad certificate chain correctly
 * reads as DOWN in production. Tests point at a self-signed stub and override
 * {@link #probeImplicitTlsConnection} rather than this class trusting all certificates.
 *
 * <p>This indicator lives in its own {@code notification} health group (skillars-deferred-96 AC2) so
 * it stays out of the deploy {@code smoke} aggregate.
 *
 * <h2>Story ses-1.2 AC2</h2>
 *
 * Moved unchanged in logic from {@code platform.notification.health.SmtpHealthIndicator}. The
 * {@code @ConditionalOnProperty} below is updated to kebab-case ({@code provider-configs[0].host}, not
 * {@code providerConfigs[0].host}) to match {@link SmtpProperties}'s new {@code app.email.smtp} YAML
 * shape: {@code @ConditionalOnProperty}'s relaxed binding does <strong>not</strong> bridge
 * camelCase↔kebab-case across an indexed ({@code [0]}) segment (verified empirically — an
 * {@code ApplicationContextRunner} with a camelCase-named indexed conditional against a kebab-case-set
 * property never activates the bean). Leaving the old camelCase literal here would silently and
 * permanently disable this indicator once the backing YAML key moved to kebab-case. The conditional's
 * semantics are unchanged — gated on a provider being configured, not on the transport enum; Phase 3
 * is what adds a transport-gate alongside it.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.email.smtp", name = "provider-configs[0].host")
public class SmtpHealthIndicator extends AbstractHealthIndicator {

	private static final int SOCKET_TIMEOUT_MS = 5000;
	private static final int CONNECT_TIMEOUT_MS = 5000;
	private static final int IMPLICIT_TLS_PORT = 465;

	private final SmtpProperties smtpProperties;
	private final SmtpHealthProperties healthProperties;
	private final ExecutorService probePool;
	private final AtomicReference<Cached> cache = new AtomicReference<>();

	@Autowired
	public SmtpHealthIndicator(SmtpProperties smtpProperties, SmtpHealthProperties healthProperties) {
		this.smtpProperties = smtpProperties;
		this.healthProperties = healthProperties;
		int size = Math.max(1, healthProperties.getProbePoolSize());
		AtomicInteger n = new AtomicInteger();
		this.probePool = Executors.newFixedThreadPool(size, r -> {
			Thread t = new Thread(r, "smtp-health-probe-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	/** Retains the pre-AC5 single-arg shape for the hermetic unit tests, with default tuning. */
	SmtpHealthIndicator(SmtpProperties smtpProperties) {
		this(smtpProperties, new SmtpHealthProperties());
	}

	@PreDestroy
	void shutdown() {
		probePool.shutdownNow();
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) {
		Cached current = cache.get();
		long now = System.currentTimeMillis();
		if (current != null && now - current.computedAtMillis() < healthProperties.getTtl().toMillis()) {
			copyInto(builder, current.health());
			return;
		}
		synchronized (cache) {
			// Double-check after acquiring lock: another thread may have just refreshed the cache
			current = cache.get();
			now = System.currentTimeMillis();
			if (current != null && now - current.computedAtMillis() < healthProperties.getTtl().toMillis()) {
				copyInto(builder, current.health());
				return;
			}
			Health fresh = computeAggregate();
			cache.set(new Cached(fresh, System.currentTimeMillis()));
			copyInto(builder, fresh);
		}
	}

	private static void copyInto(Health.Builder builder, Health source) {
		builder.status(source.getStatus());
		source.getDetails().forEach(builder::withDetail);
	}

	private Health computeAggregate() {
		List<ProviderConfig> configs = smtpProperties.getProviderConfigs();
		if (configs == null || configs.isEmpty()) {
			return Health.unknown().withDetail("reason", "No email providers configured").build();
		}

		List<Callable<ProviderStatus>> tasks = new ArrayList<>();
		for (ProviderConfig config : configs) {
			tasks.add(() -> checkProvider(config));
		}

		List<ProviderStatus> statuses = new ArrayList<>();
		try {
			List<Future<ProviderStatus>> futures =
				probePool.invokeAll(tasks, overallTimeoutMillis(), TimeUnit.MILLISECONDS);
			for (int i = 0; i < futures.size(); i++) {
				Future<ProviderStatus> f = futures.get(i);
				String name = configs.get(i).getName();
				try {
					statuses.add(f.get());
				} catch (CancellationException timedOut) {
					statuses.add(new ProviderStatus(name, "DOWN", "probe exceeded overall timeout"));
				} catch (Exception e) {
					statuses.add(new ProviderStatus(name, "DOWN",
						e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Health.unknown().withDetail("reason", "probe interrupted").build();
		}

		boolean hasUp = statuses.stream().anyMatch(s -> "UP".equals(s.status));
		boolean hasDown = statuses.stream().anyMatch(s -> "DOWN".equals(s.status));
		Health.Builder b = hasUp ? Health.up() : hasDown ? Health.down() : Health.unknown();
		return b.withDetail("providers", statuses).build();
	}

	private long overallTimeoutMillis() {
		if (healthProperties.getOverallTimeout() != null) {
			return Math.max(1, healthProperties.getOverallTimeout().toMillis());
		}
		// Probes run in parallel, so the budget is one provider's worst case plus a fixed slack.
		return (long) CONNECT_TIMEOUT_MS + SOCKET_TIMEOUT_MS + 1000L;
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
			if (isImplicitTls(config, port)) {
				return probeImplicitTlsConnection(host, port)
					? new ProviderStatus(config.getName(), "UP", "TLS handshake successful")
					: new ProviderStatus(config.getName(), "DOWN", "TLS handshake failed");
			}
			return probeSmtpConnection(host, port)
				? new ProviderStatus(config.getName(), "UP", "SMTP EHLO successful")
				: new ProviderStatus(config.getName(), "DOWN", "SMTP EHLO failed");
		} catch (NumberFormatException e) {
			return new ProviderStatus(config.getName(), "UNKNOWN", "Invalid port number: " + portStr);
		} catch (Exception e) {
			String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			return new ProviderStatus(config.getName(), "DOWN", detail);
		}
	}

	private static boolean isImplicitTls(ProviderConfig config, int port) {
		return config.getImplicitTls() != null ? config.getImplicitTls() : port == IMPLICIT_TLS_PORT;
	}

	/**
	 * Opens a TCP connection to {@code host:port}, reads the SMTP {@code 220} banner and performs
	 * an {@code EHLO} handshake, returning {@code true} only when the server answers {@code 250}.
	 *
	 * <p>Package-private and non-{@code private} on purpose: it is a network seam of this indicator,
	 * so unit tests override it to exercise the UP / DOWN / error rollup without opening a socket.
	 * Nothing else should call it.
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
	 * Opens an {@link SSLSocket} to {@code host:port} using the JVM's default trust store and treats
	 * a completed handshake as reachable — the correct probe for an implicit-TLS (port 465) endpoint,
	 * which expects TLS immediately and sends no plaintext {@code 220}.
	 *
	 * <p>Package-private seam: tests against a self-signed stub override this rather than making the
	 * production probe trust every certificate.
	 */
	boolean probeImplicitTlsConnection(String host, int port) throws IOException {
		SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
		try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
			socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
			socket.setSoTimeout(SOCKET_TIMEOUT_MS);
			socket.startHandshake();
			return socket.getSession() != null && socket.getSession().isValid();
		} catch (SocketTimeoutException e) {
			log.warn("SMTP(S) TLS handshake to {}:{} timed out", host, port, e);
			return false;
		} catch (IOException e) {
			log.warn("SMTP(S) TLS handshake to {}:{} failed: {}", host, port, e.getMessage(), e);
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

	/** Aggregate {@link Health} plus the {@link System#currentTimeMillis()} it was computed at. */
	private record Cached(Health health, long computedAtMillis) {
	}
}
