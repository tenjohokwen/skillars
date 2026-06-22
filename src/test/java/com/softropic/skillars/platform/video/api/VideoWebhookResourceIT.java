package com.softropic.skillars.platform.video.api;

import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.VideoWebhookStatus;
import com.softropic.skillars.platform.video.repo.VideoWebhookEventRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.video.webhook.processor-delay-ms=86400000",
    "app.video.reconciliation.fixed-delay-ms=86400000",
    "app.video.upload.expiry-scheduler-delay-ms=86400000"
})
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class VideoWebhookResourceIT extends BaseVideoIT {

    private static final String WEBHOOK_URL = "/api/video/webhooks/bunny";
    private static final String SIGNING_SECRET = "test-webhook-signing-secret";

    @Autowired MockMvc mockMvc;
    @Autowired VideoWebhookEventRepository webhookEventRepository;

    @BeforeEach
    void setUp() {
        webhookEventRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        webhookEventRepository.deleteAll();
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM main.refresh_tokens");
            jdbcTemplate.execute("DELETE FROM main.login_attempts");
            jdbcTemplate.update("DELETE FROM main.user_authority");
            jdbcTemplate.update("DELETE FROM main.\"user\"");
            jdbcTemplate.execute("DELETE FROM main.authority");
            jdbcTemplate.execute("DELETE FROM main.sec");
            return null;
        });
    }


        @Test
    void validWebhook_encodingSuccess_returns200AndEnqueuesEvent() throws Exception {
        String payload = "{\"VideoLibraryId\":12345,\"VideoGuid\":\"guid-test\",\"Status\":3}";
        String signature = computeHmac(SIGNING_SECRET, payload);

        mockMvc.perform(post(WEBHOOK_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-BunnyStream-Signature", signature)
                .content(payload))
            .andExpect(status().isOk());

        var events = webhookEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.iterator().next().getEventType()).isEqualTo("video.encoding.success");
        assertThat(events.iterator().next().getStatus()).isEqualTo(VideoWebhookStatus.PENDING);
    }

    @Test
    void invalidSignature_returns400AndDiscardsEvent() throws Exception {
        String payload = "{\"VideoLibraryId\":12345,\"VideoGuid\":\"guid-bad-sig\",\"Status\":3}";

        mockMvc.perform(post(WEBHOOK_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-BunnyStream-Signature", "0".repeat(64))
                .content(payload))
            .andExpect(status().isBadRequest());

        assertThat(webhookEventRepository.count()).isZero();
    }

    @Test
    void malformedSignatureHeader_returns400() throws Exception {
        String payload = "{\"VideoLibraryId\":12345,\"VideoGuid\":\"guid-malformed\",\"Status\":3}";

        mockMvc.perform(post(WEBHOOK_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-BunnyStream-Signature", "not-hex!!!")
                .content(payload))
            .andExpect(status().isBadRequest());

        assertThat(webhookEventRepository.count()).isZero();
    }

    @Test
    void duplicateDelivery_bothReturn200_onlyOneRowStored() throws Exception {
        String payload = "{\"VideoLibraryId\":12345,\"VideoGuid\":\"guid-dup\",\"Status\":3}";
        String signature = computeHmac(SIGNING_SECRET, payload);

        mockMvc.perform(post(WEBHOOK_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-BunnyStream-Signature", signature)
                .content(payload))
            .andExpect(status().isOk());

        mockMvc.perform(post(WEBHOOK_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-BunnyStream-Signature", signature)
                .content(payload))
            .andExpect(status().isOk());

        assertThat(webhookEventRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateDelivery_bothReturn200_onlyOneRowStored() throws Exception {
        String payload = "{\"VideoLibraryId\":12345,\"VideoGuid\":\"guid-concurrent\",\"Status\":7}";
        String signature = computeHmac(SIGNING_SECRET, payload);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    mockMvc.perform(post(WEBHOOK_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-BunnyStream-Signature", signature)
                            .content(payload))
                        .andExpect(status().isOk());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown(); // release both threads simultaneously
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(successCount.get()).isEqualTo(2);
        assertThat(webhookEventRepository.count()).isEqualTo(1);
    }

    private String computeHmac(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hmac);
    }
}
