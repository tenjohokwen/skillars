package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.contract.SecurityProperties;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import io.micrometer.core.annotation.Timed;
import io.micrometer.observation.annotation.Observed;

/**
 * Service for user administration operations.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final SecurityProperties securityProperties;

    /**
     * Deletes a user by login.
     * Requires ADMIN, LTD_ADMIN, or USER role.
     *
     * @param login the user login to delete
     */
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public void deleteUserInformation(final String login) {
        userRepository.findOneByLogin(login).ifPresent(u -> {
            userRepository.delete(u);
        });
    }

    /**
     * Locks a user account.
     * Requires ADMIN or LTD_ADMIN role.
     * Delegates to User domain entity for locking logic.
     *
     * @param login the user login to lock
     * @return the locked user if found, empty otherwise
     */
    @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
    public Optional<User> lockUserAccount(String login) {
        return userRepository.findOneByLogin(login)
                .map(u -> {
                    u.lock(); // Use domain method
                    return u;
                });
    }

    /**
     * Removes users that have not been activated after the configured expiration period.
     * This is a scheduled task that runs daily at 01:00 AM.
     * <p>
     * Fixed transaction boundaries (Phase 4.2):
     * 1. Uses pagination to limit fetched amount (configurable batch size)
     * 2. Each deletion happens in its own transaction (reduces lock time)
     * 3. Processes in batches to avoid memory issues with large datasets
     * 4. Continues processing until no more expired users are found
     * </p>
     * <p>
     * Note: For multi-node deployments, consider adding a distributed lock
     * (e.g., using ShedLock) to ensure only one node executes this job.
     * </p>
     */
    @Scheduled(cron = "0 0 1 * * ?")
    @Timed
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void removeNotActivatedUsers() {
        ZonedDateTime cutoffDate = ZonedDateTime.now()
                .minusDays(securityProperties.getAccountActivationExpirationDays());
        int batchSize = securityProperties.getUserCleanupBatchSize();
        int totalDeleted = 0;

        boolean hasMore = true;
        while (hasMore) {
            List<User> users = findExpiredUsers(cutoffDate, batchSize);

            if (users.isEmpty()) {
                hasMore = false;
            } else {
                for (User user : users) {
                    try {
                        deleteUserInTransaction(user.getLogin());
                        totalDeleted++;
                    } catch (Exception e) {
                        log.error("Failed to delete non-activated user",
                            kv("operation", "user_admin"),
                            kv("action", "delete_inactive"),
                            kv("status", "ERROR"),
                            e);
                        // Continue processing other users even if one fails
                    }
                }
            }
        }

    }

    /**
     * Finds expired non-activated users in a separate read-only transaction.
     * This reduces the transaction scope and allows quick queries.
     */
    @Transactional(readOnly = true)
    protected List<User> findExpiredUsers(ZonedDateTime cutoffDate, int batchSize) {
        Pageable pageable = PageRequest.of(0, batchSize);
        return userRepository.findAllByActivatedIsFalseAndCreatedDateBefore(cutoffDate)
                .stream()
                .limit(batchSize)
                .toList();
    }

    /**
     * Deletes a user in its own transaction to minimize lock duration.
     * Uses REQUIRES_NEW to ensure each deletion is independent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void deleteUserInTransaction(String login) {
        userRepository.findOneByLogin(login).ifPresent(user -> {
            userRepository.delete(user);
        });
    }
}
