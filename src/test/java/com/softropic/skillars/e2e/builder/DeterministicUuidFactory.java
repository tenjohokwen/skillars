package com.softropic.skillars.e2e.builder;

import java.util.UUID;

/**
 * Generates deterministic UUIDs for test data reproducibility (BUILD-08).
 *
 * <p>Each test class declares its own factory with a unique seed constant:
 * <pre>
 *     private final DeterministicUuidFactory uuids = new DeterministicUuidFactory(19_02L);
 * </pre>
 *
 * <p>The counter is instance-level (not static) — no shared mutable state across test instances.
 * Call {@link #reset()} in {@code @BeforeEach} if the same sequence must be replayed.
 */
public class DeterministicUuidFactory {

    private final long seed;
    private long counter = 0;

    public DeterministicUuidFactory(long seed) {
        this.seed = seed;
    }

    /**
     * Returns the next deterministic UUID.
     * UUID is constructed as {@code new UUID(seed, counter)} so the sequence is
     * fully reproducible given the same seed.
     */
    public UUID next() {
        return new UUID(seed, counter++);
    }

    /**
     * Resets the counter to zero so the same UUID sequence can be replayed.
     * Typically called in {@code @BeforeEach}.
     */
    public void reset() {
        counter = 0;
    }
}
