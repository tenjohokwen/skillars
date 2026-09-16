package com.softropic.skillars.platform.notification.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.ses.SesAccountChecker;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.MailManager;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * skillars-deferred-114 AC5: replaces {@code docs/deployment/runbook.md}'s "Pre-production release
 * gate: SES cutover" step 3 — a purely manual checklist ("send one real test email … and confirm
 * both delivery and {@code GET /actuator/health/notification} reports UP", which its own wording
 * flags as "not proof a send just worked" since that endpoint is TTL-cached) — with a single
 * executable check: a live (uncached) SES account check plus a real test send through the actual
 * production send path, in one call.
 *
 * <h2>Why the account check is delegated to {@link SesAccountChecker}</h2>
 *
 * {@code EmailTransportArchitectureTest} confines the AWS SES v2 SDK (package {@code sesv2}, under
 * {@code software.amazon.awssdk.services}) to {@code infrastructure.ses} and forbids any
 * {@code platform/**} class from referencing it directly — a real containment rule, not just
 * documentation (spelled out here across two sentences deliberately, not as one matchable literal —
 * see that test's own {@code fqcnToken} javadoc). This resource therefore never imports the SES SDK
 * itself; {@link SesAccountChecker#checkLive()} (in {@code infrastructure.ses}) does the
 * live {@code GetAccount} call — deliberately bypassing
 * {@link com.softropic.skillars.infrastructure.ses.SesHealthIndicator}'s TTL cache (up to 60s for a
 * healthy result, 15s for {@code DOWN} — exactly the staleness this endpoint exists to avoid relying
 * on during a cutover) — and returns a plain, SDK-free {@code AccountStatus} record this resource
 * can consume without crossing that boundary.
 *
 * <h2>Why the test send reuses {@link MailManager#sendEmailSync} rather than a bypass</h2>
 *
 * The whole point of a preflight check is to exercise the actual path production traffic will use —
 * the real circuit breaker, the real retry template, the real {@code SesEmailSender} — not a
 * shortcut that could pass while the real path is still broken. {@link EmailTemplate#SES_CUTOVER_PREFLIGHT}
 * carries its own isolated circuit-breaker name ({@code "sesPreflightService"}) precisely so a
 * failing probe (the point of testing an unverified config) cannot trip the shared
 * {@code "emailService"} breaker used by real booking/transactional traffic during this exact
 * cutover window; the distinct template/breaker name doubles as the filter an operator greps or
 * dashboards on to exclude preflight sends from production-traffic views.
 *
 * <h2>Why a fresh {@code sendId} every call</h2>
 *
 * {@code envelope_entity.send_id} carries a real DB {@code UNIQUE} constraint, and
 * {@code MailManager.sendEmailSync} branches its entire behavior on whether a row already exists for
 * that {@code sendId}. A reused/hardcoded value would route a second run into the "existing row"
 * update branch instead of a fresh probe attempt — re-running this tool after fixing a config issue
 * would silently stop being an independent test. {@link UUID#randomUUID()} on every call avoids that.
 *
 * <h2>Why this is transport-gated and admin-only</h2>
 *
 * {@code @ConditionalOnProperty(app.email.transport=ses)} mirrors
 * {@link com.softropic.skillars.infrastructure.ses.SesHealthIndicator}'s own bean-gating (as does
 * {@link SesAccountChecker} itself): this endpoint only exists in a profile where SES is actually
 * the active transport. {@code @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)} follows
 * {@link AlertRuleAdminResource}'s sibling admin-resource pattern.
 */
@Slf4j
@Observed(name = "http.admin.ses_preflight")
@RestController
@RequestMapping("/v1/admin/ses/preflight")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
@ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")
@RequiredArgsConstructor
public class SesCutoverPreflightResource {

    private static final String ENFORCEMENT_STATUS_SHUTDOWN = "SHUTDOWN";

    private final SesAccountChecker sesAccountChecker;
    private final MailManager mailManager;
    private final EnvelopeEntityRepository envelopeEntityRepository;

    public record SesCutoverPreflightRequest(@NotBlank @Email String recipientEmail) {
    }

    /** {@code error} is non-null only when {@code GetAccount} itself threw. */
    public record AccountCheckResult(boolean sendingEnabled, boolean productionAccessEnabled,
                                      String enforcementStatus, String error) {
    }

    /** {@code status} is {@code "SENT"} or {@code "FAILED"}; {@code error} is non-null only when FAILED. */
    public record TestSendResult(String status, String error) {
    }

    public record SesPreflightResult(AccountCheckResult accountCheck, TestSendResult testSend, boolean ready) {
    }

    @PostMapping
    public ResponseEntity<SesPreflightResult> runPreflight(@RequestBody @Valid SesCutoverPreflightRequest request) {
        AccountCheckResult accountCheck = toAccountCheckResult(sesAccountChecker.checkLive());
        TestSendResult testSend = sendTestEmail(request.recipientEmail());
        // Mirrors SesHealthIndicator's own three-condition UP check, plus a genuinely successful
        // test send — "ready" means both the account and the actual send path have been verified
        // live, not one or the other.
        boolean ready = accountCheck.error() == null
            && accountCheck.sendingEnabled()
            && accountCheck.productionAccessEnabled()
            && accountCheck.enforcementStatus() != null
            && !ENFORCEMENT_STATUS_SHUTDOWN.equalsIgnoreCase(accountCheck.enforcementStatus())
            && "SENT".equals(testSend.status());
        return ResponseEntity.ok(new SesPreflightResult(accountCheck, testSend, ready));
    }

    private static AccountCheckResult toAccountCheckResult(SesAccountChecker.AccountStatus status) {
        return new AccountCheckResult(
            status.sendingEnabled(), status.productionAccessEnabled(), status.enforcementStatus(), status.error());
    }

    private TestSendResult sendTestEmail(String recipientEmail) {
        Recipient recipient = new Recipient();
        recipient.setEmail(recipientEmail);
        recipient.setLangKey("en");

        // skillars-deferred-114 AC5: a fresh sendId on every call — see class javadoc.
        String sendId = "ses-preflight-" + UUID.randomUUID();
        Envelope envelope = new Envelope(List.of(recipient), EmailTemplate.SES_CUTOVER_PREFLIGHT,
            Instant.now().plus(EmailTemplate.SES_CUTOVER_PREFLIGHT.deliveryDeadline()), Map.of(), sendId);

        // The real production send path — real circuit breaker (isolated "sesPreflightService"
        // breaker), real retry template, real SesEmailSender. sendEmailSync never rethrows: it
        // catches every exception itself and persists the outcome, so the outcome has to be read
        // back rather than inferred from a normal return (same pattern as
        // VideoModerationEmailListener.sendAdminAlertSync).
        mailManager.sendEmailSync(envelope);

        // skillars-deferred-114 code review (LOW): this read-back is not itself wrapped in a
        // transaction/lock, so it carries no stronger visibility guarantee than a plain READ
        // COMMITTED read of whatever mailManager.sendEmailSync's own REQUIRES_NEW transaction
        // committed just above (same shape VideoModerationEmailListener.sendAdminAlertSync's own
        // null-read-back branch documents — see that method's AC5 analysis). A null here is expected
        // to be rare, not a proven structural gap; the message below says so explicitly rather than
        // implying an unqualified failure, so an operator re-running this after a null result knows a
        // retry — not a config fix — is the first thing to try.
        EnvelopeEntity persisted = envelopeEntityRepository.findBySendId(sendId);
        if (persisted == null) {
            log.warn("[SES_CUTOVER_PREFLIGHT] send outcome not yet visible on read-back for sendId={}", sendId);
            return new TestSendResult("FAILED",
                "send outcome not yet visible on read-back (possible visibility lag — retry) sendId=" + sendId);
        }
        if (persisted.getStatus() == EmailDeliveryStatus.SENT) {
            return new TestSendResult("SENT", null);
        }
        return new TestSendResult("FAILED", persisted.getError());
    }
}
