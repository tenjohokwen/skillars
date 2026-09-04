package com.softropic.skillars.platform.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.infrastructure.listener.BookingEmailListener;
import com.softropic.skillars.platform.notification.infrastructure.listener.SessionPackEmailListener;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport.NotificationEmailPayload;

import org.instancio.Instancio;
import org.instancio.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * skillars-deferred-92 AC2 — the transactional-email {@code data} map must survive the outbox JSON
 * round trip with its value types intact.
 *
 * <p>{@link NotificationOutboxSupport#enqueueEmail} serialises {@code Map<String,Object>} to JSON and
 * {@code NotificationEmailOutboxHandler} deserialises it back, so a value's Java type is whatever
 * Jackson infers from the JSON: an {@code Instant} returns as a {@code String}, a {@code BigDecimal}
 * as a {@code Double} ({@code 40.00} → {@code 40.0}), a small {@code Long} as an {@code Integer}.
 * Nothing caught that, and nothing would have caught the first template that added such a value.
 *
 * <h2>Correcting AC2's premise</h2>
 *
 * The story states that "all 69 {@code data.put(...)} call sites across {@code BookingEmailListener}
 * and {@code SessionPackEmailListener} put a {@code String} or a {@code List<String>}". Re-counted
 * against source: there are <strong>72</strong> {@code data.put} calls, and three keys —
 * {@code requestedCount}, {@code acceptedCount} and {@code creditsRemaining} — put a primitive
 * {@code int}, boxed to {@code Integer} ({@code BatchBookingRequestedEvent:14},
 * {@code BatchBookingAcceptedEvent:20}, {@code SessionPackExpiredEvent:15} /
 * {@code SessionPackExpiryWarningEvent:16}). They are still <em>correct</em> — {@code Integer}
 * round-trips as {@code Integer} — so there is no live bug, but "the map is all strings" is not the
 * contract the code actually keeps.
 *
 * <p>This test therefore asserts the property AC2 is really after — <strong>the post-round-trip type
 * equals the pre-round-trip type</strong> — rather than the narrower "everything is a String", which
 * would fail today against correct code.
 */
@DisplayName("Email template data must survive the outbox JSON round trip unchanged")
class EmailDataRoundTripContractTest {

    /**
     * Mirrors the application's mapper: {@code application.yaml} sets
     * {@code spring.jackson.serialization.WRITE_DATES_AS_TIMESTAMPS: false}. Without that this test
     * would model a serialisation the application does not perform (an {@code Instant} written as an
     * epoch {@code Double} rather than an ISO string) and would document the wrong failure mode.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules()
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** A zone every listener's {@code formatInstantInZone} can resolve; random strings cannot. */
    private static final String ZONE = "Europe/Berlin";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> roundTrip(Map<String, Object> data) {
        try {
            NotificationEmailPayload payload = new NotificationEmailPayload(
                EmailTemplate.BOOKING_REMINDER.name(), "x@example.com", "en",
                UUID.randomUUID().toString(), Instant.now(), data);
            String json = MAPPER.writeValueAsString(payload);
            return MAPPER.readValue(json, NotificationEmailPayload.class).data();
        } catch (Exception e) {
            throw new IllegalStateException("round trip failed for " + data, e);
        }
    }

    /**
     * A value's type as the templates care about it. Collections are described by their element type
     * rather than their concrete class, because {@code List.of(...)} in and {@code ArrayList} out is
     * not a fidelity loss — {@code List<Instant>} in and {@code List<String>} out is, and this still
     * catches that.
     */
    private static boolean declaresField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String describe(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof java.util.Collection<?> c) {
            return "List<" + (c.isEmpty() ? "?" : describe(c.iterator().next())) + ">";
        }
        if (v instanceof Map<?, ?>) {
            return "Map";
        }
        return v.getClass().getSimpleName();
    }

    private static void assertTypesSurvive(Map<String, Object> before, String origin) {
        Map<String, Object> after = roundTrip(before);

        assertThat(after.keySet()).as("%s: keys must survive", origin).isEqualTo(before.keySet());
        before.forEach((key, value) -> assertThat(describe(after.get(key)))
            .as("""
                %s: '%s' went into the outbox as a %s and came back as a %s. Email template data is \
                string-typed by contract — format numbers and instants AT THE PRODUCER, not in the \
                template. An Instant becomes an ISO blob and a BigDecimal loses its scale \
                (40.00 -> 40.0), with nothing else in the codebase to catch it.""",
                origin, key, describe(value), describe(after.get(key)))
            .isEqualTo(describe(value)));
    }

    /**
     * The primary assertion: drive every {@code on*} handler on both listeners for real, capture every
     * {@code data} map it produces, and round-trip each one. This is AC2's "walk every {@code data} map
     * produced by the two listeners" rather than its weaker fallback.
     */
    @Test
    @DisplayName("every data map both listeners actually produce round-trips with its types intact")
    void everyProducedDataMapSurvivesTheRoundTrip() throws Exception {
        NotificationOutboxSupport support = mock(NotificationOutboxSupport.class);
        BookingEmailListener bookingListener = new BookingEmailListener(support, "http://localhost", "8080");
        SessionPackEmailListener packListener = new SessionPackEmailListener(support);

        List<String> invoked = new ArrayList<>();
        for (Object listener : List.of(bookingListener, packListener)) {
            for (Method m : listener.getClass().getDeclaredMethods()) {
                if (!m.getName().startsWith("on") || m.getParameterCount() != 1) {
                    continue;
                }
                Class<?> eventType = m.getParameterTypes()[0];
                if (!org.springframework.context.ApplicationEvent.class.isAssignableFrom(eventType)) {
                    continue;
                }
                // Instancio fills every field, including the non-null emails the listeners guard on, so
                // each handler runs its full body. canonicalTimezone must be a real zone id or
                // formatInstantInZone throws and the handler's catch-all swallows the whole payload —
                // which would leave this test silently asserting nothing. The captured-count assertion
                // below is what makes that failure visible rather than invisible.
                // The selector is applied only where the field exists: Instancio rejects a selector
                // naming a field the target class does not declare, even in lenient mode, and only
                // some of these events carry a timezone.
                org.instancio.InstancioApi<?> spec = Instancio.of(eventType);
                if (declaresField(eventType, "canonicalTimezone")) {
                    spec = spec.set(Select.field("canonicalTimezone"), ZONE);
                }
                Object event = spec.create();
                m.setAccessible(true);
                m.invoke(listener, event);
                invoked.add(listener.getClass().getSimpleName() + "." + m.getName());
            }
        }

        assertThat(invoked)
            .as("all 23 on*(Event) handlers across both listeners must have been driven")
            .hasSize(23);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(support, atLeastOnce())
            .enqueueEmail(any(EmailTemplate.class), any(Recipient.class), captor.capture(), anyString());

        List<Map<String, Object>> maps = captor.getAllValues();
        assertThat(maps)
            .as("""
                Every handler swallowed its payload before reaching enqueueEmail, so this test would \
                have asserted nothing. Most likely Instancio produced a value a handler rejects (a \
                blank email, an unparseable zone) — fix the fixture, do not lower this bound.""")
            .hasSizeGreaterThanOrEqualTo(20);

        for (int i = 0; i < maps.size(); i++) {
            assertTypesSurvive(maps.get(i), "captured data map #" + i);
        }
    }

    /**
     * Documents the actual round-trip behaviour of the types a template author might reach for, so the
     * contract in {@link NotificationOutboxSupport}'s javadoc is a checked claim rather than a comment.
     * If Jackson's configuration ever changed, this is what would notice.
     */
    @Test
    @DisplayName("the safe types survive and the lossy ones demonstrably do not")
    void roundTripFidelityByType() {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("aString", "hello");
        safe.put("aStringList", List.of("a", "b"));
        safe.put("anInt", 42);
        assertTypesSurvive(safe, "safe types");

        Map<String, Object> lossy = new LinkedHashMap<>();
        lossy.put("anInstant", Instant.parse("2026-09-04T10:15:30Z"));
        lossy.put("aBigDecimal", new BigDecimal("40.00"));
        lossy.put("aUuid", UUID.randomUUID());
        lossy.put("aSmallLong", 7L);
        Map<String, Object> after = roundTrip(lossy);

        assertThat(after.get("anInstant"))
            .as("an Instant returns as an ISO String — format it at the producer")
            .isInstanceOf(String.class);
        assertThat(after.get("aBigDecimal"))
            .as("a BigDecimal returns as a Double: 40.00 becomes 40.0 and the money loses its scale")
            .isInstanceOf(Double.class);
        assertThat(after.get("aUuid")).isInstanceOf(String.class);
        assertThat(after.get("aSmallLong"))
            .as("a Long small enough to fit an int returns as an Integer")
            .isInstanceOf(Integer.class);
    }
}
