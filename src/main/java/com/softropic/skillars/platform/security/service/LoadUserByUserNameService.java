package com.softropic.skillars.platform.security.service;



import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;


/**
 * Authenticate a user from the database.
 */
@Service
public class LoadUserByUserNameService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    @Transactional
    public UserDetails loadUserByUsername(final String loginId) {
        final String lowercaseLogin = loginId.toLowerCase();
        final Optional<User> userFromDbOpt = userRepository.findOneByLogin(lowercaseLogin);
        if(userFromDbOpt.isPresent()) {
            final User user = userFromDbOpt.get();
            return Principal.instanceFrom(user);
        }
        throw new UsernameNotFoundException("User " + lowercaseLogin + " was not found in the database");
    }
}
