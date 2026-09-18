package com.softropic.skillars.platform.security.repo;


import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.security.contract.LoginIdType;
import com.softropic.skillars.platform.security.contract.SkillarsRole;
import com.softropic.skillars.platform.security.contract.SkillarsVerificationStatus;

import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static jakarta.persistence.CascadeType.DETACH;
import static jakarta.persistence.CascadeType.PERSIST;
import static jakarta.persistence.CascadeType.REFRESH;


/**
 * A user.
 */
@Audited
@Entity
@Table(name = "user")
public class User extends Customer implements Serializable {

    //TODO lastLogins (loginTime:LocalDateTime, sessionId:text, explicitLogout:boolean, RequestMetadata:json, logoutTime:localDateTime, credId:uuid, blacklisted:boolean)
    //TODO give the following at registration (username, password, email,langkey)
    //Please note that the email address used as the username is the one associate to the user at the user creation time. If users update their email address in their My Profile area, the username is not updated to reflect the new email address.
    @NotNull
    @Email(regexp = "[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,3}", flags = Pattern.Flag.CASE_INSENSITIVE)
    @Size(min = 6, max = 100)
    @Column(length = 100, unique = true, nullable = false, columnDefinition = "text")
    private String login;

    @NotNull
    @Enumerated(EnumType.STRING)
    private LoginIdType loginIdType;

    @NotNull
    @Size(min = 60, max = 60) 
    @Column(name = "password_hash",length = 60, columnDefinition = "text")
    private String password;

    @Column(nullable = false)
    private boolean activated = false;

    @Column(nullable = false)
    private boolean locked = false;

    @Size(max = 20)
    @Column(name = "activation_key", length = 20, columnDefinition = "text")
    private String activationKey;

    @Column(name = "activation_date")
    private Instant activationDate;

    @Size(max = 20)
    @Column(name = "reset_key", length = 20, columnDefinition = "text")
    private String resetKey;

    @Column(name = "reset_expiration")
    private Instant resetExpiration = null;

    @Column(name = "account_expiration")
    private Instant accountExpiration = null;

    /**
     * skillars-deferred-122 AC9: a marker set once {@code UserAdminService}'s daily non-activated-user
     * cleanup sweep has failed to delete this user on
     * {@link #cleanupFailedAttempts}/CLEANUP_FAILURE_THRESHOLD separate scheduled runs (code review
     * 2026-09-18: not on the first failure — see {@link #cleanupFailedAttempts}'s Javadoc for why).
     * Excludes the row from every subsequent run's candidate set so a deterministically-undeletable
     * user no longer produces the identical recurring ERROR log on every run, and gives operators
     * something to query: {@code SELECT * FROM main."user" WHERE cleanup_failed_at IS NOT NULL}. No
     * automatic retry/clear mechanism — an operator fixes the underlying issue or clears the marker
     * manually. {@code @NotAudited}: this is operational marker state, not user-facing auditable
     * history — {@code main.user_aud} (Envers) has no matching column and none is added by this story.
     */
    @NotAudited
    @Column(name = "cleanup_failed_at")
    private Instant cleanupFailedAt;

    /**
     * skillars-deferred-122 AC9 code review 2026-09-18: consecutive separate-run cleanup-delete
     * failure count for this user, incremented once per scheduled run this user fails to delete (never
     * more than once per run — {@code UserAdminService}'s in-run {@code failedLogins} set already
     * excludes an already-failed login from the rest of that same run). Replaces a one-shot {@link
     * #cleanupFailedAt} stamp on first failure, which could not distinguish a deterministically-
     * undeletable user from a transient one (a lock timeout, deadlock, or connection reset) — see
     * {@code UserAdminService.recordCleanupFailure}'s Javadoc for the full rationale. Follows this
     * repo's established idiom for repeatedly-failing work ({@code OutboxReplicationJob.attemptCount},
     * {@code VideoWebhookEvent.attemptCount}, {@code Video.moderationRetryCount}).
     */
    @NotAudited
    @Column(name = "cleanup_failed_attempts", nullable = false)
    private int cleanupFailedAttempts = 0;

    /** skillars-deferred-122 AC9 code review 2026-09-18: when {@link #cleanupFailedAttempts} was last incremented. */
    @NotAudited
    @Column(name = "cleanup_last_attempted_at")
    private Instant cleanupLastAttemptedAt;

    /**
     * skillars-deferred-122 AC9 code review 2026-09-18: the most recent cleanup-delete failure's
     * exception message — operator-diagnostic only, truncated by the writer
     * ({@code UserAdminService.MAX_CLEANUP_ERROR_MESSAGE_LENGTH}), not a full stack trace.
     */
    @NotAudited
    @Column(name = "cleanup_last_error", columnDefinition = "text")
    private String cleanupLastError;

    private boolean otpEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "skillars_role")
    private SkillarsRole skillarsRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status")
    private SkillarsVerificationStatus verificationStatus = SkillarsVerificationStatus.UNVERIFIED;

    @ManyToMany(cascade = {REFRESH, DETACH, PERSIST}, fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_authority",
        joinColumns = @JoinColumn(name = "user_id", referencedColumnName = "id"),
        inverseJoinColumns = @JoinColumn(name = "authority_id", referencedColumnName = "id"))
    private Set<Authority> authorities = new HashSet<>();

    @Valid
    @JsonManagedReference
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, mappedBy = "user", fetch = FetchType.LAZY)
    private Set<PersistentToken> persistentTokens = new HashSet<>();

    public String getLogin() {
        return login;
    }

    public void setLogin(final String login) {
        this.login = login;
    }

    public @NotNull LoginIdType getLoginIdType() {
        return loginIdType;
    }

    public void setLoginIdType(@NotNull LoginIdType loginIdType) {
        this.loginIdType = loginIdType;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(final String password) {
        this.password = password;
    }

    public boolean isActivated() {
        return activated;
    }

    public void setActivated(final boolean activated) {
        this.activated = activated;
    }

    public String getActivationKey() {
        return activationKey;
    }

    public void setActivationKey(final String activationKey) {
        this.activationKey = activationKey;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(final boolean locked) {
        this.locked = locked;
    }

    public String getResetKey() {
        return resetKey;
    }

    public void setResetKey(final String resetKey) {
        this.resetKey = resetKey;
    }

    public Instant getResetExpiration() {
       return resetExpiration;
    }

    public void setResetExpiration(final Instant resetExpiration) {
       this.resetExpiration = resetExpiration;
    }

    public Instant getAccountExpiration() {
        return accountExpiration;
    }

    public void setAccountExpiration(Instant accountExpiration) {
        this.accountExpiration = accountExpiration;
    }

    public Set<Authority> getAuthorities() {
        return authorities;
    }

    public void setAuthorities(final Set<Authority> authorities) {
        this.authorities = authorities;
    }

    public Set<PersistentToken> getPersistentTokens() {
        return persistentTokens;
    }

    public void setPersistentTokens(final Set<PersistentToken> persistentTokens) {
        this.persistentTokens = persistentTokens;
    }

    public Instant getActivationDate() {
        return activationDate;
    }

    public void setActivationDate(final Instant activationDate) {
        this.activationDate = activationDate;
    }

    public boolean hasAccountExpired() {
        return accountExpiration != null && Instant.now(ClockProvider.getClock()).isAfter(accountExpiration);
    }

    public Instant getCleanupFailedAt() {
        return cleanupFailedAt;
    }

    public void setCleanupFailedAt(final Instant cleanupFailedAt) {
        this.cleanupFailedAt = cleanupFailedAt;
    }

    public int getCleanupFailedAttempts() {
        return cleanupFailedAttempts;
    }

    public void setCleanupFailedAttempts(final int cleanupFailedAttempts) {
        this.cleanupFailedAttempts = cleanupFailedAttempts;
    }

    public Instant getCleanupLastAttemptedAt() {
        return cleanupLastAttemptedAt;
    }

    public void setCleanupLastAttemptedAt(final Instant cleanupLastAttemptedAt) {
        this.cleanupLastAttemptedAt = cleanupLastAttemptedAt;
    }

    public String getCleanupLastError() {
        return cleanupLastError;
    }

    public void setCleanupLastError(final String cleanupLastError) {
        this.cleanupLastError = cleanupLastError;
    }

    public boolean isOtpEnabled() {
        return otpEnabled;
    }

    public void setOtpEnabled(boolean otpEnabled) {
        this.otpEnabled = otpEnabled;
    }

    public SkillarsRole getSkillarsRole() {
        return skillarsRole;
    }

    public void setSkillarsRole(SkillarsRole skillarsRole) {
        this.skillarsRole = skillarsRole;
    }

    public SkillarsVerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(SkillarsVerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    // ==================== BUSINESS METHODS ====================

    /**
     * Activates this user account.
     * Sets the account as activated, clears the activation key, and sets activation date.
     *
     * @throws com.softropic.skillars.platform.security.contract.exception.UserAlreadyActivatedException if user is already activated
     */
    public void activate() {
        if (this.activated) {
            throw new com.softropic.skillars.platform.security.contract.exception.UserAlreadyActivatedException(
                "User is already activated", this.login);
        }
        this.activated = true;
        this.activationKey = null;
        this.activationDate = Instant.now(ClockProvider.getClock());
    }

    /**
     * Locks this user account, preventing login.
     * Locked accounts cannot reset passwords or activate.
     */
    public void lock() {
        this.locked = true;
    }

    /**
     * Unlocks this user account, allowing login again.
     */
    public void unlock() {
        this.locked = false;
    }

    /**
     * Prepares this account for password reset by generating and setting a reset key.
     * The reset key will be valid for the specified duration.
     *
     * @param resetKey the generated reset key
     * @param expirationHours number of hours the reset key is valid
     * @throws com.softropic.skillars.platform.security.contract.exception.UserNotActivatedException if account is not activated
     * @throws com.softropic.skillars.platform.security.contract.exception.UserAccountLockedException if account is locked
     */
    public void preparePasswordReset(String resetKey, long expirationHours) {
        if (!this.activated) {
            throw new com.softropic.skillars.platform.security.contract.exception.UserNotActivatedException(
                "Cannot reset password for inactive account", this.login);
        }
        if (this.locked) {
            throw new com.softropic.skillars.platform.security.contract.exception.UserAccountLockedException(
                "Cannot reset password for locked account", this.login);
        }
        this.resetKey = resetKey;
        this.resetExpiration = Instant.now(ClockProvider.getClock()).plus(expirationHours, ChronoUnit.HOURS);
    }

    /**
     * Completes the password reset process by setting a new password.
     * Clears the reset key and expiration after successful reset.
     *
     * @param newPasswordHash the new password hash
     * @throws com.softropic.skillars.platform.security.contract.exception.UserAccountLockedException if account is locked
     * @throws com.softropic.skillars.platform.security.contract.exception.PasswordResetExpiredException if reset key has expired
     */
    public void completePasswordReset(String newPasswordHash) {
        if (this.locked) {
            throw new com.softropic.skillars.platform.security.contract.exception.UserAccountLockedException(
                "Cannot reset password for locked account", this.login);
        }
        if (this.resetExpiration == null ||
            Instant.now(ClockProvider.getClock()).isAfter(this.resetExpiration)) {
            throw new com.softropic.skillars.platform.security.contract.exception.PasswordResetExpiredException(
                "Password reset key has expired", this.login);
        }
        this.password = newPasswordHash;
        this.resetKey = null;
        this.resetExpiration = null;
    }

    /**
     * Checks if the password reset key is still valid.
     *
     * @return true if reset key exists and has not expired
     */
    public boolean hasValidResetKey() {
        return this.resetKey != null &&
               this.resetExpiration != null &&
               Instant.now(ClockProvider.getClock()).isBefore(this.resetExpiration);
    }

    /**
     * Checks if this user can perform password reset.
     *
     * @return true if user is activated, not locked, and doesn't have a valid reset key
     */
    public boolean canInitiatePasswordReset() {
        return this.activated && !this.locked && !hasValidResetKey();
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof User user)) {
            return false;
        }
        // Use login as business key for User identity (null-safe comparison)
        return Objects.equals(login, user.login);
    }

    @Override
    public int hashCode() {
        // Use a constant hashCode for JPA entities to avoid issues when fields change
        // This ensures entities work correctly in HashSet/HashMap
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "{\"User\":"
                + super.toString()
                + ", \"login\":\"" + login + "\""
                + ", \"loginIdType\":\"" + loginIdType + "\""
                + ", \"password\": \"[REDACTED]\""
                + ", \"activated\":\"" + activated + "\""
                + ", \"locked\":\"" + locked + "\""
                + ", \"activationKey\":\"" + activationKey + "\""
                + ", \"activationDate\":" + activationDate
                + ", \"resetKey\":\"" + resetKey + "\""
                + ", \"resetExpiration\":" + resetExpiration
                + ", \"accountExpiration\":" + accountExpiration
                + ", \"otpEnabled\":\"" + otpEnabled + "\""
                + ", \"authorities\":" + authorities
                + ", \"persistentTokens\":" + persistentTokens
                + ", \"firstName\":\"" + firstName + "\""
                + ", \"lastName\":\"" + lastName + "\""
                + ", \"title\":\"" + title + "\""
                + ", \"gender\":\"" + gender + "\""
                + ", \"dateOfBirth\":" + dateOfBirth
                + ", \"langKey\":\"" + langKey + "\""
                + ", \"phone\":" + phone
                + ", \"email\":\"" + email + "\""
                + ", \"addresses\":" + addresses
                + ", \"createdBy\":\"" + createdBy + "\""
                + ", \"createdDate\":" + createdDate
                + ", \"lastModifiedBy\":\"" + lastModifiedBy + "\""
                + ", \"lastModifiedDate\":" + lastModifiedDate
                + ", \"requestId\":\"" + requestId + "\""
                + ", \"status\":\"" + status + "\""
                + "}";
    }
}
