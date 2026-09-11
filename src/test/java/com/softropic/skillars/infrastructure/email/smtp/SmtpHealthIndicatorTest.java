package com.softropic.skillars.infrastructure.email.smtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermetic unit tests for {@link SmtpHealthIndicator}. The single network seam
 * ({@link SmtpHealthIndicator#probeSmtpConnection}) is overridden per test, so nothing here opens
 * a socket, resolves DNS or depends on an external mail server — the whole class runs offline in
 * the Surefire {@code test} phase.
 *
 * <p>Story ses-1.2 AC2: moved from {@code platform.notification.health.SmtpHealthIndicatorTest} —
 * logic unchanged, only the package and the {@code EmailProperties -> SmtpProperties} rename.
 */
@DisplayName("SMTP Health Indicator")
class SmtpHealthIndicatorTest {

	private final SmtpProperties smtpProperties = new SmtpProperties();

	private static ProviderConfig provider(String name, String host, String port) {
		ProviderConfig c = new ProviderConfig();
		c.setName(name);
		c.setHost(host);
		c.setPort(port);
		return c;
	}

	/** Indicator whose probe result is decided by {@code probe} instead of a real connection. */
	private SmtpHealthIndicator indicatorWithProbe(BiPredicate<String, Integer> probe) {
		return new SmtpHealthIndicator(smtpProperties) {
			@Override
			boolean probeSmtpConnection(String host, int port) {
				return probe.test(host, port);
			}
		};
	}

	@SuppressWarnings("unchecked")
	private static List<SmtpHealthIndicator.ProviderStatus> providers(Health health) {
		return (List<SmtpHealthIndicator.ProviderStatus>) health.getDetails().get("providers");
	}

	// ------------------------------------------------------------------ UNKNOWN / misconfiguration

	@Test
	@DisplayName("no providers configured -> UNKNOWN with a reason")
	void noProviders() {
		SmtpHealthIndicator indicator = indicatorWithProbe((h, p) -> true);

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(new Status("UNKNOWN"));
		assertThat(health.getDetails()).containsEntry("reason", "No email providers configured");
	}

	@Test
	@DisplayName("missing host -> UNKNOWN, probe never invoked")
	void missingHost() {
		smtpProperties.setProviderConfigs(List.of(provider("incomplete", null, "587")));
		SmtpHealthIndicator indicator = indicatorWithProbe((h, p) -> {
			throw new AssertionError("probe must not run for a misconfigured provider");
		});

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(new Status("UNKNOWN"));
		assertThat(providers(health)).singleElement().satisfies(s -> {
			assertThat(s.status).isEqualTo("UNKNOWN");
			assertThat(s.detail).isEqualTo("Host or port not configured");
		});
	}

	@Test
	@DisplayName("non-numeric port -> UNKNOWN")
	void invalidPort() {
		smtpProperties.setProviderConfigs(List.of(provider("badport", "mail.example.com", "invalid")));
		SmtpHealthIndicator indicator = indicatorWithProbe((h, p) -> true);

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(new Status("UNKNOWN"));
		assertThat(providers(health)).singleElement()
			.satisfies(s -> assertThat(s.detail).isEqualTo("Invalid port number: invalid"));
	}

	@Test
	@DisplayName("port out of range -> UNKNOWN, not DOWN")
	void portOutOfRange() {
		smtpProperties.setProviderConfigs(List.of(provider("badport", "mail.example.com", "99999")));
		SmtpHealthIndicator indicator = indicatorWithProbe((h, p) -> true);

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(new Status("UNKNOWN"));
		assertThat(providers(health)).singleElement()
			.satisfies(s -> assertThat(s.detail).isEqualTo("Port out of range: 99999"));
	}

	// -------------------------------------------------------------------------------- UP / DOWN

	@Test
	@DisplayName("probe succeeds -> UP with EHLO-success detail")
	void providerUp() {
		smtpProperties.setProviderConfigs(List.of(provider("gmx", "mail.gmx.net", "587")));
		SmtpHealthIndicator indicator = indicatorWithProbe((h, p) -> true);

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(providers(health)).singleElement().satisfies(s -> {
			assertThat(s.name).isEqualTo("gmx");
			assertThat(s.status).isEqualTo("UP");
			assertThat(s.detail).isEqualTo("SMTP EHLO successful");
		});
	}

	@Test
	@DisplayName("probe returns false -> DOWN with EHLO-failed detail")
	void providerDown() {
		smtpProperties.setProviderConfigs(List.of(provider("gmx", "mail.gmx.net", "587")));
		SmtpHealthIndicator indicator = indicatorWithProbe((h, p) -> false);

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(providers(health)).singleElement().satisfies(s -> {
			assertThat(s.status).isEqualTo("DOWN");
			assertThat(s.detail).isEqualTo("SMTP EHLO failed");
		});
	}

	@Test
	@DisplayName("probe throws IOException -> DOWN, message carried into detail")
	void probeThrowsIoException() {
		smtpProperties.setProviderConfigs(List.of(provider("gmx", "mail.gmx.net", "587")));
		SmtpHealthIndicator indicator = new SmtpHealthIndicator(smtpProperties) {
			@Override
			boolean probeSmtpConnection(String host, int port) throws IOException {
				throw new IOException("connection refused");
			}
		};

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(providers(health)).singleElement()
			.satisfies(s -> assertThat(s.detail).isEqualTo("connection refused"));
	}

	@Test
	@DisplayName("probe throws with null message -> DOWN, detail falls back to a non-null string")
	void probeThrowsWithNullMessage() {
		smtpProperties.setProviderConfigs(List.of(provider("gmx", "mail.gmx.net", "587")));
		SmtpHealthIndicator indicator = new SmtpHealthIndicator(smtpProperties) {
			@Override
			boolean probeSmtpConnection(String host, int port) {
				throw new IllegalStateException(); // getMessage() == null
			}
		};

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(providers(health)).singleElement().satisfies(s -> {
			assertThat(s.detail).isNotNull().isEqualTo("IllegalStateException");
		});
	}

	@Test
	@DisplayName("provider with null name -> ProviderStatus.name defaults to \"unknown\"")
	void nullProviderNameDefaulted() {
		smtpProperties.setProviderConfigs(List.of(provider(null, "mail.gmx.net", "587")));
		SmtpHealthIndicator indicator = indicatorWithProbe((h, p) -> false);

		Health health = indicator.health();

		assertThat(providers(health)).singleElement()
			.satisfies(s -> assertThat(s.name).isEqualTo("unknown"));
	}

	// ------------------------------------------------------------------------- multi-provider rollup

	@Test
	@DisplayName("any provider UP -> aggregate UP")
	void anyUpIsUp() {
		smtpProperties.setProviderConfigs(List.of(
			provider("gmx", "mail.gmx.net", "587"),
			provider("gmail", "smtp.gmail.com", "587")));
		SmtpHealthIndicator indicator = indicatorWithProbe((host, p) -> "mail.gmx.net".equals(host));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(providers(health)).extracting(s -> s.status)
			.containsExactly("UP", "DOWN");
	}

	@Test
	@DisplayName("all providers DOWN -> aggregate DOWN")
	void allDownIsDown() {
		smtpProperties.setProviderConfigs(List.of(
			provider("gmx", "mail.gmx.net", "587"),
			provider("gmail", "smtp.gmail.com", "587")));
		SmtpHealthIndicator indicator = indicatorWithProbe((h, p) -> false);

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(providers(health)).hasSize(2)
			.allSatisfy(s -> assertThat(s.status).isEqualTo("DOWN"));
	}

	// --------------------------------------------------------------------------- readSmtpLine parsing

	@Test
	@DisplayName("readSmtpLine returns a single CRLF-terminated line without the terminator")
	void readLineStripsCrlf() throws IOException {
		var in = new ByteArrayInputStream("220 mx.example.com ESMTP\r\n".getBytes(StandardCharsets.US_ASCII));

		assertThat(SmtpHealthIndicator.readSmtpLine(in)).isEqualTo("220 mx.example.com ESMTP");
	}

	@Test
	@DisplayName("readSmtpLine reads one line at a time from a multiline 220- greeting")
	void readLineHandlesMultilineGreeting() throws IOException {
		var in = new ByteArrayInputStream(
			"220-mx.example.com welcome\r\n220 ready\r\n".getBytes(StandardCharsets.US_ASCII));

		assertThat(SmtpHealthIndicator.readSmtpLine(in)).isEqualTo("220-mx.example.com welcome");
		assertThat(SmtpHealthIndicator.readSmtpLine(in)).isEqualTo("220 ready");
	}

	@Test
	@DisplayName("readSmtpLine returns the buffered content when the stream ends without a newline")
	void readLineReturnsContentAtEof() throws IOException {
		var in = new ByteArrayInputStream("250 OK".getBytes(StandardCharsets.US_ASCII));

		assertThat(SmtpHealthIndicator.readSmtpLine(in)).isEqualTo("250 OK");
	}

	@Test
	@DisplayName("readSmtpLine returns null on an empty stream")
	void readLineReturnsNullOnEmptyStream() throws IOException {
		var in = new ByteArrayInputStream(new byte[0]);

		assertThat(SmtpHealthIndicator.readSmtpLine(in)).isNull();
	}

	// ------------------------------------------------------------------ skillars-deferred-99 AC5

	@Test
	@DisplayName("AC5: providers are probed in parallel — N slow probes cost ≈ one, not N")
	void probesRunInParallel() {
		smtpProperties.setProviderConfigs(List.of(
			provider("a", "a.example.com", "587"),
			provider("b", "b.example.com", "587"),
			provider("c", "c.example.com", "587")));
		SmtpHealthIndicator indicator = new SmtpHealthIndicator(smtpProperties, new SmtpHealthProperties()) {
			@Override
			boolean probeSmtpConnection(String host, int port) {
				try {
					Thread.sleep(400);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return true;
			}
		};

		long start = System.nanoTime();
		Health health = indicator.health();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(elapsedMs)
			.as("3 × 400ms probes running in parallel must finish well under the 1200ms serial cost")
			.isLessThan(1000);
	}

	@Test
	@DisplayName("AC5: a second call inside the TTL is served from cache — no re-probe")
	void ttlCacheDedupesProbes() {
		smtpProperties.setProviderConfigs(List.of(provider("gmx", "mail.gmx.net", "587")));
		java.util.concurrent.atomic.AtomicInteger probeCount = new java.util.concurrent.atomic.AtomicInteger();
		var props = new SmtpHealthProperties();
		props.setTtl(java.time.Duration.ofSeconds(60));
		SmtpHealthIndicator indicator = new SmtpHealthIndicator(smtpProperties, props) {
			@Override
			boolean probeSmtpConnection(String host, int port) {
				probeCount.incrementAndGet();
				return true;
			}
		};

		Health first = indicator.health();
		Health second = indicator.health();

		assertThat(first.getStatus()).isEqualTo(Status.UP);
		assertThat(second.getStatus()).isEqualTo(Status.UP);
		assertThat(probeCount.get()).as("the second scrape inside the TTL must not re-probe").isEqualTo(1);
	}

	@Test
	@DisplayName("AC5: a port-465 provider is probed with a TLS handshake, not the plaintext banner")
	void implicitTlsProviderUsesHandshakeProbe() {
		smtpProperties.setProviderConfigs(List.of(provider("smtps", "smtps.example.com", "465")));
		SmtpHealthIndicator up = new SmtpHealthIndicator(smtpProperties, new SmtpHealthProperties()) {
			@Override
			boolean probeSmtpConnection(String host, int port) {
				throw new AssertionError("a 465 provider must not take the plaintext EHLO path");
			}
			@Override
			boolean probeImplicitTlsConnection(String host, int port) {
				return true;
			}
		};

		Health health = up.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(providers(health)).singleElement()
			.satisfies(s -> assertThat(s.detail).isEqualTo("TLS handshake successful"));
	}

	@Test
	@DisplayName("AC5: a failed TLS handshake on port 465 -> DOWN")
	void implicitTlsHandshakeFailureIsDown() {
		smtpProperties.setProviderConfigs(List.of(provider("smtps", "smtps.example.com", "465")));
		SmtpHealthIndicator down = new SmtpHealthIndicator(smtpProperties, new SmtpHealthProperties()) {
			@Override
			boolean probeImplicitTlsConnection(String host, int port) {
				return false;
			}
		};

		Health health = down.health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(providers(health)).singleElement()
			.satisfies(s -> assertThat(s.detail).isEqualTo("TLS handshake failed"));
	}
}
