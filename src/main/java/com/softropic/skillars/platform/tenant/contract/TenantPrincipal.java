package com.softropic.skillars.platform.tenant.contract;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security {@link UserDetails} implementation representing an authenticated tenant.
 *
 * <p>Created by {@code ApiKeyAuthenticationFilter} after a successful API key authentication.
 * The {@code username} is the tenant's UUID reference ({@code tenantRef}) rather than a
 * human-readable name, ensuring uniqueness across the system.
 *
 * <p>No password is stored — the API key credential has already been validated before this
 * principal is constructed.
 */
public class TenantPrincipal implements UserDetails {

    private final String tenantRef;    // UUID from Tenant.tenantRef
    private final Long tenantId;       // DB id of the Tenant row
    private final Collection<? extends GrantedAuthority> authorities;

    public TenantPrincipal(String tenantRef, Long tenantId) {
        this.tenantRef   = tenantRef;
        this.tenantId    = tenantId;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_TENANT"));
    }

    public String getTenantRef() { return tenantRef; }
    public Long getTenantId()    { return tenantId; }

    // UserDetails contract — username is tenantRef
    @Override public String getUsername()  { return tenantRef; }
    @Override public String getPassword()  { return null; }   // no password; key already authenticated
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return true; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
}
