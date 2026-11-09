package com.softropic.skillars.platform.security.service;

import org.apache.commons.lang3.StringUtils;
import java.util.Locale;
import com.softropic.skillars.platform.security.contract.Consumer;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.contract.exception.UserNotFoundException;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import com.softropic.skillars.platform.security.repo.AuthorityRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;


/**
 * Service class for managing users.
 * Provides read-only user query operations.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final SecurityUtil securityUtil;




    @Transactional(readOnly = true)
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public Optional<User> findUserWithAuthoritiesByLogin(final String login) {
        return userRepository.findOneByLogin(StringUtils.lowerCase(login, Locale.ROOT));
    }

    @Transactional(readOnly = true)
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public User getUserWithAuthorities(final Long id) {
        return userRepository.findOneById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @Transactional(readOnly = true)
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public User getUserWithAuthorities() {
        return userRepository.findOneByLogin(securityUtil.getCurrentUserName())
                .orElseThrow(() -> new UserNotFoundException(securityUtil.getCurrentUserName()));
    }

    @Transactional(readOnly = true)
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public User getUserWithAuthoritiesAndAddresses() {
        //EntityGraphType.FETCH treats all unlisted attributes  as LAZY, overriding the @ElementCollection(fetch = EAGER)
        //annotation on the addresses field
        final User user = userRepository.findOneByLogin(securityUtil.getCurrentUserName())
                                        .orElseThrow(() -> new UserNotFoundException(securityUtil.getCurrentUserName()));
        //This should fetch addresses as well since "addresses" should be loaded eagerly
        //Hibernate will then combine the addresses and authorities
        return userRepository.findOneById(user.getId()).get();
    }

    @Transactional(readOnly = true)
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public Optional<User> findUserByEmail(final String email) {
        // skillars-deferred-172: addresses are stored lowercased (V231); match what the user typed in any casing.
        return userRepository.findOneByEmail(StringUtils.lowerCase(email, Locale.ROOT));
    }

    @Transactional(readOnly = true)
    public Optional<User> findUserByLogin(String login) {
        // skillars-deferred-172: addresses are stored lowercased (V231); match what the user typed in any casing.
        return userRepository.findOneByLogin(StringUtils.lowerCase(login, Locale.ROOT));
    }

    @Transactional(readOnly = true)
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public Optional<User> findUserById(Long id) {
        return userRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<User> findUsersByIds(List<Long> ids) {
        return userRepository.findAllById(ids);
    }

    public Optional<Consumer> findCustomerByLogin(String login) {
        return this.userRepository.findCustomerByLogin(login);
    }

    public boolean exists(long userId) {
        return userRepository.existsById(userId);
    }
}
