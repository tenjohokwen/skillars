package com.softropic.skillars.platform.payment.api;

import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.security.SecurityIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * fix/payment-stripe-connect-webhook-endpoint: the Connect webhook endpoint end to end, through the
 * real security chain (permit-all, no JWT), signature check and idempotency table. Secrets come from
 * application-test.yaml ({@code webhook-secret: whsec_test}, {@code connect-webhook-secret:
 * whsec_test_connect}). Adds no mocks, so it shares the base payment context.
 */
// The JWT signing secret row: SecurityAdviceFilter loads it on every request, permit-all included
// (same fixture and teardown as VideoWebhookResourceIT). @Sql is not part of the context cache key.
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class StripeConnectWebhookIT extends BasePaymentIT {

    private static final String CONNECT_URL = "/api/payment/webhooks/stripe/connect";
    private static final String PLATFORM_URL = "/api/payment/webhooks/stripe";
    private static final String CONNECT_SECRET = "whsec_test_connect";
    private static final String PLATFORM_SECRET = "whsec_test";
    private static final long COACH_USER_ID = 88_301L;

    // MockMvc comes from @AutoConfigureMockMvc on AbstractIntegrationTest.
    @Autowired MockMvc mockMvc;
    @Autowired CoachStripeAccountRepository coachStripeAccountRepository;

    private UUID coachId;
    private String stripeAccountId;
    private String eventId;

    @BeforeEach
    void seed() {
        coachId = insertTestCoach(COACH_USER_ID, "connect-webhook-it@example.com", "Connect Webhook IT");
        stripeAccountId = "acct_it_" + UUID.randomUUID().toString().substring(0, 8);
        eventId = "evt_it_" + UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("INSERT INTO payment.coach_stripe_accounts "
                + "(coach_id, stripe_account_id, onboarding_status) VALUES (?, ?, 'PENDING')",
                coachId, stripeAccountId);
            return null;
        });
    }

    @AfterEach
    void cleanUp() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("DELETE FROM payment.stripe_webhook_events WHERE event_id = ?", eventId);
            jdbcTemplate.update("DELETE FROM admin.admin_alerts WHERE reference_id = ?", coachId.toString());
            jdbcTemplate.update("DELETE FROM payment.coach_stripe_accounts WHERE coach_id = ?", coachId);
            jdbcTemplate.update("DELETE FROM marketplace.coach_profiles WHERE id = ?", coachId);
            jdbcTemplate.update("DELETE FROM main.\"user\" WHERE id = ?", COACH_USER_ID);
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }

    @Test
    void connectEndpoint_signedAccountUpdated_completesOnboarding_andRedeliveryIsDeduplicated() throws Exception {
        String payload = accountUpdated(true, true);

        postSigned(CONNECT_URL, payload, CONNECT_SECRET).andExpect(status().isOk());

        Map<String, Object> row = accountRow();
        assertThat(row.get("onboarding_status")).isEqualTo("COMPLETE");
        assertThat(row.get("charges_enabled")).isEqualTo(true);
        assertThat(row.get("transfers_capability")).isEqualTo("active");
        assertThat(eventRows()).isEqualTo(1);

        // Stripe redelivery of the same event: acknowledged, recorded once.
        postSigned(CONNECT_URL, payload, CONNECT_SECRET).andExpect(status().isOk());
        assertThat(eventRows()).isEqualTo(1);
    }

    /**
     * fix/prod-readiness-gaps: the keyset query behind CoachStripeAccountResyncScheduler, against real
     * Postgres uuid ordering. The nil-UUID start finds a COMPLETE account; a disconnected one is skipped.
     */
    @Test
    void resyncQuery_findsConnectedCompleteAccounts_skipsDeauthorized() {
        transactionTemplate.execute(status -> jdbcTemplate.update("UPDATE payment.coach_stripe_accounts "
            + "SET onboarding_status = 'COMPLETE', transfers_capability = 'active' WHERE coach_id = ?", coachId));

        assertThat(resyncCandidates()).contains(coachId);

        transactionTemplate.execute(status -> jdbcTemplate.update("UPDATE payment.coach_stripe_accounts "
            + "SET transfers_capability = 'deauthorized' WHERE coach_id = ?", coachId));

        assertThat(resyncCandidates()).doesNotContain(coachId);
    }

    private java.util.List<UUID> resyncCandidates() {
        return coachStripeAccountRepository.findConnectedByStatusAfter("COMPLETE", "deauthorized",
                new UUID(0L, 0L), PageRequest.of(0, 1000))
            .stream().map(CoachStripeAccount::getCoachId).toList();
    }

    @Test
    void connectEndpoint_deauthorized_makesAccountNotPayable_raisesOneCoachAlert_evenOnRedelivery() throws Exception {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE payment.coach_stripe_accounts SET onboarding_status = 'COMPLETE', "
                + "charges_enabled = true, payouts_enabled = true, transfers_capability = 'active' "
                + "WHERE coach_id = ?", coachId);
            return null;
        });
        String payload = """
            {
              "id": "%s",
              "object": "event",
              "type": "account.application.deauthorized",
              "account": "%s",
              "created": %d,
              "data": { "object": { "id": "ca_it", "object": "application", "name": "Skillars" } }
            }
            """.formatted(eventId, stripeAccountId, Instant.now().getEpochSecond());

        postSigned(CONNECT_URL, payload, CONNECT_SECRET).andExpect(status().isOk());
        postSigned(CONNECT_URL, payload, CONNECT_SECRET).andExpect(status().isOk());

        Map<String, Object> row = accountRow();
        assertThat(row.get("onboarding_status")).isEqualTo("RESTRICTED");
        assertThat(row.get("charges_enabled")).isEqualTo(false);
        assertThat(row.get("transfers_capability")).isEqualTo("deauthorized");
        assertThat(eventRows()).isEqualTo(1);
        // The alert is inserted AFTER_COMMIT in its own transaction; the request has returned by then.
        Map<String, Object> alert = jdbcTemplate.queryForMap("SELECT type, reference_type, reason, "
            + "(SELECT COUNT(*) FROM admin.admin_alerts a2 WHERE a2.reference_id = a.reference_id) AS n "
            + "FROM admin.admin_alerts a WHERE reference_id = ?", coachId.toString());
        assertThat(alert.get("type")).isEqualTo("PAYOUT_HELD");
        assertThat(alert.get("reference_type")).isEqualTo("COACH");
        assertThat((String) alert.get("reason")).startsWith("STRIPE_DEAUTHORIZED");
        assertThat(((Number) alert.get("n")).intValue()).isEqualTo(1);
    }

    @Test
    void connectEndpoint_signedWithPlatformSecret_rejected400_noStateChange() throws Exception {
        postSigned(CONNECT_URL, accountUpdated(true, true), PLATFORM_SECRET).andExpect(status().isBadRequest());

        assertThat(accountRow().get("onboarding_status")).isEqualTo("PENDING");
        assertThat(eventRows()).isZero();
    }

    @Test
    void platformEndpoint_signedWithConnectSecret_rejected400() throws Exception {
        postSigned(PLATFORM_URL, accountUpdated(true, true), CONNECT_SECRET).andExpect(status().isBadRequest());

        assertThat(accountRow().get("onboarding_status")).isEqualTo("PENDING");
        assertThat(eventRows()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions postSigned(String url, String payload, String secret)
            throws Exception {
        return mockMvc.perform(post(url)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", sign(secret, payload))
            .content(payload));
    }

    private Map<String, Object> accountRow() {
        return jdbcTemplate.queryForMap("SELECT onboarding_status, charges_enabled, transfers_capability "
            + "FROM payment.coach_stripe_accounts WHERE coach_id = ?", coachId);
    }

    private int eventRows() {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.stripe_webhook_events WHERE event_id = ?", Integer.class, eventId);
        return n == null ? 0 : n;
    }

    private String accountUpdated(boolean charges, boolean payouts) {
        return """
            {
              "id": "%s",
              "object": "event",
              "type": "account.updated",
              "account": "%s",
              "created": %d,
              "data": {
                "object": {
                  "id": "%s",
                  "object": "account",
                  "charges_enabled": %b,
                  "payouts_enabled": %b,
                  "capabilities": { "transfers": "active" }
                }
              }
            }
            """.formatted(eventId, stripeAccountId, Instant.now().getEpochSecond(), stripeAccountId, charges, payouts);
    }

    private static String sign(String secret, String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(
            mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + sig;
    }
}
