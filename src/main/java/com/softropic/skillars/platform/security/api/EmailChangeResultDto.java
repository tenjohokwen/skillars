package com.softropic.skillars.platform.security.api;

/**
 * Answer to a used email-change link (skillars-deferred-172). {@code passwordResetSent} is set on undo only:
 * {@code false} means the account is suspended or not activated, so its password was left alone.
 */
public record EmailChangeResultDto(String code, boolean passwordResetSent) {
}
