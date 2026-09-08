package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.repo.PendingProviderAssetRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * skillars-deferred-100 AC2: records a just-created video-provider asset in its <strong>own</strong>
 * committed transaction, so the record survives a rollback of the caller's transaction (which would
 * otherwise discard the local {@code Video} / {@code UploadSession} rows and orphan the remote
 * asset forever — {@code ReconciliationWorkerScheduler} only ever looks at rows that have a
 * {@code Video}).
 *
 * <p>A separate bean on purpose: {@code REQUIRES_NEW} only suspends the caller's transaction when
 * the call goes through the Spring proxy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingProviderAssetTracker {

    private final PendingProviderAssetRepository repository;

    /**
     * Records {@code providerAssetId} in a new, immediately-committed transaction. A no-op if the
     * id is null/blank, the provider is blank, or the id is already tracked (a retry of the same
     * {@code initializeUpload}).
     *
     * <p>skillars-deferred-100 code review (2026-09-08): the insert is a native
     * {@code INSERT … ON CONFLICT (provider_asset_id) DO NOTHING}, not {@code repository.save} in a
     * {@code try/catch (DataIntegrityViolationException)}. A constraint violation inside this
     * {@code REQUIRES_NEW} transaction marks it rollback-only at the JPA level, so the old catch did
     * <em>not</em> make "already tracked" a no-op — it would surface as {@code UnexpectedRollbackException}
     * at commit and fail the whole upload. {@code ON CONFLICT DO NOTHING} never raises, so the
     * duplicate case is a genuine no-op regardless of concurrency.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String providerAssetId, String provider) {
        if (!StringUtils.hasText(providerAssetId)) {
            return;
        }
        if (!StringUtils.hasText(provider)) {
            log.warn("pending_provider_asset not tracked for providerAssetId={} — blank provider", providerAssetId);
            return;
        }
        int inserted = repository.insertIfAbsent(providerAssetId, provider);
        if (inserted == 0) {
            log.debug("pending_provider_asset already tracked for providerAssetId={}", providerAssetId);
        }
    }
}
