package com.softropic.skillars.platform.security.contract;



import com.softropic.skillars.platform.security.contract.Gender;

import org.apache.commons.text.CaseUtils;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public class Principal extends User {
    private final Gender gender;
    private final String displayName;
    private final String businessId; //user id
    private final boolean otpEnabled;
    private final String phone;
    private final LoginIdType loginIdType;
    private final SkillarsRole skillarsRole;
    private final SkillarsVerificationStatus verificationStatus;
    // skillars-deferred-149 AC3 code review (2026-10-08): the account-wide, monotonic "last
    // refresh-token-theft revocation" epoch (null if the account has never had one). NOT consulted
    // by credentialsNonExpired (reverted to hardcoded true below, after the review found that
    // binding it here re-armed a stolen JWT on the victim's own next login and separately locked
    // the still-live /authenticate endpoint out for every future login attempt). The real
    // per-session check lives in JWTAuthorizationFilter's DB-reauth branch, comparing THIS value
    // against the specific JWT's own SESSION_ISSUED_AT claim -- see that claim's own javadoc.
    private final Instant securitySessionInvalidatedAt;
    //TODO add the session id
    //Please note that the email address used as the username/login is the one associate to the user at the user creation time. If users update their email address in their My Profile area, the username is not updated to reflect the new email address.

    private Principal(Builder builder) {
        super(
                builder.username,
                builder.password,
                builder.enabled,
                builder.accountNonExpired,
                builder.credentialsNonExpired,
                builder.accountNonLocked,
                builder.authorities
        );
        this.gender = builder.gender;
        this.displayName = builder.displayName;
        this.businessId = builder.businessId;
        this.otpEnabled = builder.otpEnabled;
        this.phone = builder.phone;
        this.loginIdType = builder.loginIdType;
        this.skillarsRole = builder.skillarsRole;
        this.verificationStatus = builder.verificationStatus;
        this.securitySessionInvalidatedAt = builder.securitySessionInvalidatedAt;
    }

    public static class Builder {
        // Fields required by User
        private String username;
        private String password;
        private Boolean enabled;
        private boolean accountNonExpired = true;
        private boolean credentialsNonExpired = true;
        private boolean accountNonLocked = true;
        private Collection<? extends GrantedAuthority> authorities = new HashSet<>();

        // Additional fields for Principal
        private Gender gender;
        private String displayName;
        private String businessId;
        private Boolean otpEnabled;
        private String phone;
        private LoginIdType loginIdType = LoginIdType.EMAIL;
        private SkillarsRole skillarsRole;
        private SkillarsVerificationStatus verificationStatus;
        private Instant securitySessionInvalidatedAt;

        // Builder methods for User fields
        public Builder username(String username) {
            this.username = username;
            return this;
        }
        public Builder password(String password) {
            this.password = password;
            return this;
        }
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        public Builder accountNonExpired(boolean accountNonExpired) {
            this.accountNonExpired = accountNonExpired;
            return this;
        }
        public Builder credentialsNonExpired(boolean credentialsNonExpired) {
            this.credentialsNonExpired = credentialsNonExpired;
            return this;
        }
        public Builder accountNonLocked(boolean accountNonLocked) {
            this.accountNonLocked = accountNonLocked;
            return this;
        }
        public Builder authorities(Collection<? extends GrantedAuthority> authorities) {
            this.authorities = authorities;
            return this;
        }

        // Builder methods for Principal fields
        public Builder gender(Gender gender) {
            this.gender = gender;
            return this;
        }
        public Builder displayName(String displayName) {
            this.displayName = CaseUtils.toCamelCase(displayName, true);
            return this;
        }
        public Builder businessId(String businessId) {
            this.businessId = businessId;
            return this;
        }
        public Builder otpEnabled(boolean otpEnabled) {
            this.otpEnabled = otpEnabled;
            return this;
        }
        public Builder phone(String phone) {
            this.phone = phone;
            return this;
        }

        public Builder loginType(LoginIdType loginIdType) {
            this.loginIdType = loginIdType;
            return this;
        }

        public Builder skillarsRole(SkillarsRole role) {
            this.skillarsRole = role;
            return this;
        }

        public Builder verificationStatus(SkillarsVerificationStatus status) {
            this.verificationStatus = status;
            return this;
        }

        public Builder securitySessionInvalidatedAt(Instant securitySessionInvalidatedAt) {
            this.securitySessionInvalidatedAt = securitySessionInvalidatedAt;
            return this;
        }

        public Principal build() {
            return new Principal(this);
        }
    }

    public static Principal instanceFrom(com.softropic.skillars.platform.security.repo.User user) {
        final List<GrantedAuthority> grantedAuthorities = user.getAuthorities()
                                                              .stream()
                                                              .map(authority ->
                                                                           new SimpleGrantedAuthority(authority.getName()))
                                                              .map(GrantedAuthority.class::cast).toList();
        return new Builder().username(user.getLogin().toLowerCase())
                            .password(user.getPassword())
                            .enabled(user.isActivated())
                            .accountNonExpired(!user.hasAccountExpired())
                            // skillars-deferred-149 AC3 code review (2026-10-08): reverted to
                            // hardcoded true (was briefly bound to securitySessionInvalidatedAt ==
                            // null). That bound credentialsNonExpired to an ACCOUNT-level flag that
                            // AuthService.login() cleared on every successful login -- so a victim's
                            // own re-login re-armed an attacker's still-live stolen JWT, and
                            // separately locked the still-live /authenticate endpoint out of a
                            // flagged account even with correct credentials, since Spring's
                            // preAuthenticationChecks runs before the password check. The real fix
                            // is per-JWT, not per-account -- see securitySessionInvalidatedAt below
                            // and SecurityConstants.SESSION_ISSUED_AT's javadoc.
                            .credentialsNonExpired(true)
                            .accountNonLocked(!user.isLocked())
                            .authorities(grantedAuthorities)
                            .gender(user.getGender())
                            .displayName(user.getFirstName())
                            .businessId(String.valueOf(user.getId()))
                            .otpEnabled(user.isOtpEnabled())
                            .skillarsRole(user.getSkillarsRole())
                            .verificationStatus(user.getVerificationStatus())
                            .securitySessionInvalidatedAt(user.getSecuritySessionInvalidatedAt())
                            .build();
    }

    public Gender getGender() {
        return gender;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBusinessId() {
        return businessId;
    }

    public boolean isOtpEnabled() {
        return otpEnabled;
    }

    public SkillarsRole getSkillarsRole() {
        return skillarsRole;
    }

    public SkillarsVerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public Instant getSecuritySessionInvalidatedAt() {
        return securitySessionInvalidatedAt;
    }
}
