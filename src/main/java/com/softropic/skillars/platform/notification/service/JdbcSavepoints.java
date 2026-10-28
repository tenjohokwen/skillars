package com.softropic.skillars.platform.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.function.Supplier;

import javax.sql.DataSource;

/**
 * Runs plain-JDBC notification work inside a savepoint of the caller's transaction, so that a
 * failure in that work (a DB error, a bug) is rolled back <em>on its own</em> and can never doom the
 * business transaction it piggybacks on — on PostgreSQL a failed statement otherwise aborts the
 * whole transaction ("current transaction is aborted"), whatever the caller catches.
 *
 * <p>Why not {@code Propagation.NESTED}: Spring's {@code JpaTransactionManager} cannot create
 * savepoints with Hibernate ({@code HibernateJpaDialect}'s transaction data is not a
 * {@code SavepointManager} — NESTED throws {@code NestedTransactionNotSupportedException}). The JDBC
 * connection the JPA transaction exposes to {@code DataSourceUtils} supports savepoints directly.
 *
 * <p><strong>JDBC only.</strong> The work must use {@code JdbcTemplate}, never the JPA
 * {@code EntityManager} or a Spring Data query: those auto-flush the session first, and rolling
 * back to the savepoint would then silently discard business SQL Hibernate believes it flushed.
 */
@Component
@RequiredArgsConstructor
public class JdbcSavepoints {

    private final DataSource dataSource;

    /**
     * Runs {@code work} inside a savepoint when a transaction is active (rolled back to on any
     * {@link RuntimeException}, which is then rethrown), or directly when none is.
     */
    public <T> T run(final Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return work.get();
        }
        final Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            final Savepoint savepoint;
            try {
                savepoint = connection.setSavepoint();
            } catch (SQLException e) {
                throw new DataAccessResourceFailureException("could not create savepoint", e);
            }
            final T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                try {
                    connection.rollback(savepoint);
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
            try {
                connection.releaseSavepoint(savepoint);
            } catch (SQLException e) {
                throw new DataAccessResourceFailureException("could not release savepoint", e);
            }
            return result;
        } finally {
            // A no-op for the transaction-bound connection; kept for DataSourceUtils symmetry.
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
