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
        log.info("[GDPR_EXPORT] Processing export requestId={} userId={}", event.getRequestId(), event.getUserId());
        try {
            gdprExportService.buildExport(event.getRequestId(), event.getUserId());
        } catch (Exception e) {
            log.error("[GDPR_EXPORT_FAILED] requestId={} userId={}", event.getRequestId(), event.getUserId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onErasureRequested(GdprErasureRequestedEvent event) {
        log.info("[GDPR_ERASURE] Processing erasure requestId={} userId={}", event.getRequestId(), event.getUserId());
        try {
            gdprErasureService.erase(event.getRequestId(), event.getUserId());
        } catch (Exception e) {
            // CRITICAL: erase() TX has rolled back — calling markFailed() opens a fresh REQUIRES_NEW TX
            // so the FAILED status commits independently of the rolled-back erasure transaction.
            gdprErasureService.markFailed(event.getRequestId());
            log.error("[GDPR_ERASURE_FAILED] requestId={} userId={}", event.getRequestId(), event.getUserId(), e);
        }
    }
}
