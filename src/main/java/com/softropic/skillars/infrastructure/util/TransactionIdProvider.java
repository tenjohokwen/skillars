package com.softropic.skillars.infrastructure.util;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;


/**
 * Utility for managing transaction IDs in the MDC (Mapped Diagnostic Context).
 * Provides thread-safe access to transaction identifiers for logging and tracing.
 */
public final class TransactionIdProvider {

    private TransactionIdProvider() {}

    public static String provideTransactionId() {
        return Optional.ofNullable(MDC.get(Constants.TXN_ID_NAME)).orElseGet(TransactionIdProvider::addTransactionIdToThread);
    }

    private static String addTransactionIdToThread() {
        final String transactionId = UUID.randomUUID().toString();
        addTransactionIdToThread(transactionId);
        return transactionId;
    }

    public static void addTransactionIdToThread(final String transactionId) {
        MDC.put(Constants.TXN_ID_NAME, transactionId);
    }

    public static void removeTransactionIdFromThread() {
        MDC.remove(Constants.TXN_ID_NAME);
    }

}
