package com.softropic.skillars.platform.security.contract;



import com.softropic.skillars.platform.security.contract.Gender;

import org.apache.commons.text.CaseUtils;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

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
                            .credentialsNonExpired(true)
                            .accountNonLocked(!user.isLocked())
                            .authorities(grantedAuthorities)
                            .gender(user.getGender())
                            .displayName(user.getFirstName())
                            .businessId(String.valueOf(user.getId()))
                            .otpEnabled(user.isOtpEnabled())
                            .skillarsRole(user.getSkillarsRole())
                            .verificationStatus(user.getVerificationStatus())
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
}
