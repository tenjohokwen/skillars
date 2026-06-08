package com.softropic.skillars.platform.video.contract;

import io.micrometer.observation.annotation.Observed;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Contract for quota enforcement on video uploads.
 *
 * <p>Implementations <b>must</b> be concurrent-safe. Implementations must explicitly
 * document the isolation strategy and locking mechanisms used (e.g., pessimistic locking,
 * optimistic locking, or database-level atomicity).
 *
 * <p>The module orchestrates the check → reserve → commit/release sequence.
 *
 * <p>Implementations <b>must</b> implement a TTL or timeout mechanism for reservations.
 * If a reservation is not committed within the expected window, the implementation
 * must release the quota to prevent leaks. Implementations should expose a
 * maintenance method or rely on background reaper tasks to prune expired reservations.
 *
 * <p>All methods must be <b>idempotent</b>. Implementations <b>must</b> document the
 * idempotency strategy for {@code commit} and {@code release} (e.g., using operation
 * state transitions such as {@code PENDING → COMMITTED}). Failure to ensure
 * idempotency may lead to inconsistent quota states if upstream retries occur.
 *
 * <p>Input validation: {@code ownerId} must not be blank, and {@code requestedBytes} /
 * {@code bytes} must be positive.
 *
 * <p>The call sequence orchestrated by the module is:
 * <ol>
 *   <li>{@link #check(String, long)} — verify quota is available (no reservation yet)</li>
 *   <li>{@link #reserve(String, long)} — reserve the bytes; returns an opaque handle</li>
 *   <li>{@link #commit(String)} — on successful upload confirmation</li>
 *   <li>{@link #release(String)} — on upload failure, expiry, or session cancellation</li>
 * </ol>
 */
public interface QuotaProvider {

    @Observed(name = "video.quota.check")
    boolean check(@NotBlank String ownerId, @Min(1) long requestedBytes);

    @Observed(name = "video.quota.reserve")
    String reserve(@NotBlank String ownerId, @Min(1) long bytes);

    @Observed(name = "video.quota.commit")
    void commit(@NotBlank String reservationHandle);

    @Observed(name = "video.quota.release")
    void release(@NotBlank String reservationHandle);

    /**
     * Returns the consistency guarantee of this implementation.
     */
    ConsistencyGuarantee getConsistencyGuarantee();
}
