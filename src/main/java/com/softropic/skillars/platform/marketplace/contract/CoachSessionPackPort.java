package com.softropic.skillars.platform.marketplace.contract;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * skillars-deferred-168: the coach's one purchasable session pack, as the profile builder and the public
 * profile see it. The offer itself is the coach's active {@code payment.session_pack_tiers} row, so this
 * port is implemented in the payment module (marketplace takes no compile dependency on payment), exactly
 * like {@link CoachSubscriptionEntitlementPort}. Before this story the builder wrote a separate
 * {@code marketplace.session_packs} table that nothing could buy.
 */
public interface CoachSessionPackPort {

    /**
     * The coach's active offer, or empty when they have none. The label is {@code null} when the coach set none.
     */
    Optional<SessionPackDto> findActivePack(UUID coachId);

    /**
     * Sets the coach's offer, joining the caller's transaction. An empty {@code pack} withdraws the active
     * offer. A pack equal to the active one (count, price and effective label) writes nothing; any other pack
     * replaces it, keeping the old tier row for the purchases that reference it.
     */
    void replaceActivePack(UUID coachId, Optional<PackSpec> pack);

    /** A pack as the builder submits it. A null or blank {@code label} means "use the default". */
    record PackSpec(int sessionCount, BigDecimal totalPrice, String label) {
    }
}
