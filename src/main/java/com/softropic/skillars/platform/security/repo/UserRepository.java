package com.softropic.skillars.platform.security.repo;



import com.softropic.skillars.platform.security.contract.Consumer;
import com.softropic.skillars.platform.security.repo.User;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA persistence for the User entity.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findOneByActivationKey(final String activationKey);

    @Query("select u from User u where activated = false and activationKey = ?1")
    Optional<User> findInactivatedByActivationKey(final String activationKey);

    List<User> findAllByActivatedIsFalseAndCreatedDateBefore(final ZonedDateTime dateTime);

    Optional<User> findOneByResetKey(final String resetKey);

    Optional<User> findOneByEmail(final String email);

    //EntityGraphType.FETCH treats all unlisted attributes as LAZY, overriding the @ElementCollection(fetch = EAGER)
    //annotation on the addresses field. DO NOT add "addresses" to "attributePaths". I will lead to a cartesian product
    @EntityGraph(type = EntityGraph.EntityGraphType.FETCH, attributePaths = {"authorities"})
    Optional<User> findOneByLogin(final String login);

    //This should fetch addresses as well since "addresses" should be loaded eagerly
    Optional<User> findOneById(Long userId);

    Optional<User> findOneByEmailOrLogin(final String email, final String login);

    @Override
    void delete(final User user);

    Optional<Consumer> findCustomerById(Long id);

    Optional<Consumer> findCustomerByLogin(String login);

    @Query("update User  set locked = ?1 where login = ?2")
    @Modifying
    void changeAccountLockStatus(boolean locked, String username);

    @Query("update User  set otpEnabled = true where login = ?1")
    @Modifying
    void enableOtp(String username);
}
