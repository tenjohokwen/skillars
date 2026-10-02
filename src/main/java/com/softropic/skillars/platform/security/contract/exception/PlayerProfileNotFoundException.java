package com.softropic.skillars.platform.security.contract.exception;

import com.softropic.skillars.infrastructure.security.SecurityError;

/**
 * skillars-deferred-139 review D3: distinct from {@link UserNotFoundException} — thrown when the
 * user account exists but has no {@code PlayerProfile} row yet (a self-registered adult who never
 * completed the player profile builder). {@link UserNotFoundException} names the wrong entity for
 * this case ("User not found") even though the user is demonstrably logged in; this lets the
 * frontend distinguish "your account doesn't exist" from "you haven't finished your player
 * profile yet" and route to the builder instead.
 */
public class PlayerProfileNotFoundException extends UserDomainException {

    public PlayerProfileNotFoundException(Long userId) {
        super(String.format("Player profile not found for userId: %d", userId), SecurityError.USER_NOT_FOUND);
    }
}
