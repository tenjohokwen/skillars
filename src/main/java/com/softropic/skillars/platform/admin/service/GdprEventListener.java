package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.GdprErasureRequestedEvent;
import com.softropic.skillars.platform.admin.contract.GdprExportRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

// Must NOT be @Transactional — AFTER_COMMIT means no surrounding transaction is active.
// Service methods create their own transactions. Same pattern as AccountDeletionCascadeListener.
@Component
@RequiredArgsConstructor
@Slf4j
public class GdprEventListener {

    private final GdprExportService gdprExportService;
    private final GdprErasureService gdprErasureService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExportRequested(GdprExportRequestedEvent event) {
        log.info("[GDPR_EXPORT] Processing export requestId={} userId={} playerId={}",
            event.getRequestId(), event.getUserId(), event.getPlayerId());
        try {
            if (event.getPlayerId() != null) {
                gdprExportService.buildChildExport(event.getRequestId(), event.getUserId(), event.getPlayerId());
            } else {
                gdprExportService.buildExport(event.getRequestId(), event.getUserId());
            }
        } catch (Exception e) {
            log.error("[GDPR_EXPORT_FAILED] requestId={} userId={}", event.getRequestId(), event.getUserId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onErasureRequested(GdprErasureRequestedEvent event) {
        log.info("[GDPR_ERASURE] Processing erasure requestId={} userId={} playerId={}",
            event.getRequestId(), event.getUserId(), event.getPlayerId());
        try {
            // V172: a child-scoped request carries the PARENT as userId — routing it to erase() would
            // anonymise the parent's own account.
            if (event.getPlayerId() != null) {
                gdprErasureService.eraseChild(event.getRequestId(), event.getUserId(), event.getPlayerId());
            } else {
                gdprErasureService.erase(event.getRequestId(), event.getUserId());
            }
        } catch (Exception e) {
            // CRITICAL: erase() TX has rolled back — calling markFailed() opens a fresh REQUIRES_NEW TX
            // so the FAILED status commits independently of the rolled-back erasure transaction.
            gdprErasureService.markFailed(event.getRequestId());
            log.error("[GDPR_ERASURE_FAILED] requestId={} userId={}", event.getRequestId(), event.getUserId(), e);
        }
    }
}
