package com.softropic.skillars.platform.video.contract;

import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;

/**
 * Synchronous delivery of a moderation admin alert, for callers that must know whether the send
 * actually succeeded (skillars-deferred-92 code review, decision D1).
 *
 * <p>The ordinary path for this alert is fire-and-forget: {@code ModerationOrchestrationService}
 * publishes a {@link VideoModerationAdminAlertEvent}, {@code VideoModerationEmailListener} turns it
 * into an {@code Envelope}, and {@code MailManager.sendEmailFromTemplate} sends it
 * {@code @Async} + {@code AFTER_COMMIT}. That is fine when nothing is waiting on the outcome.
 *
 * <p>It is <em>not</em> fine on the outbox drain. {@code ModerationAdminAlertOutboxHandler} holds a
 * durable row whose whole purpose is "this alert has not been delivered yet", and the outbox deletes
 * that row the moment {@code handle()} returns normally. Re-publishing the event returns normally
 * before anything has been sent, so an SMTP failure minutes later marked an envelope {@code FAILED}
 * against a row that no longer existed: no {@code attempts++}, no backoff, no {@code [OUTBOX_STUCK]}.
 * The handler needs delivery to have either happened or thrown by the time it returns, which is what
 * this interface promises.
 *
 * <p>Declared in {@code platform.video.contract} and implemented in {@code platform.notification} so
 * the dependency runs the same direction as every other cross-module link here — video owns the
 * contract, notification owns the mail.
 */
public interface ModerationAdminAlertSender {

    /**
     * Sends the alert on the calling thread and reports the outcome by throwing or not throwing.
     *
     * @throws RuntimeException if the send failed in a way a later attempt could still fix, so the
     *                          caller's outbox row is retained, backed off and retried. A permanently
     *                          undeliverable alert (or one suppressed because
     *                          {@code platform.admin_alert_email} is unset) is logged at ERROR and
     *                          returns normally — no re-drive can improve on it, and an immortal row
     *                          would consume a claim slot forever.
     */
    void sendAdminAlertSync(VideoModerationAdminAlertEvent event);
}
