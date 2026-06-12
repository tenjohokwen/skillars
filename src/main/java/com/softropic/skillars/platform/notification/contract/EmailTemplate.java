package com.softropic.skillars.platform.notification.contract;

public enum EmailTemplate {
    NONE(""),
    ACTIVATION("email.activation.title"),
    CREATION_DUP("email.creation_dup.title"),
    PASSWORD_RESET("email.pw_reset.title"),
    SEND_OTP("email.otp.title"),
    COACH_EMAIL_VERIFY("email.coach.verify.title"),
    COACH_OTP("email.coach.otp.title"),
    PARENT_EMAIL_VERIFY("email.parent.verify.title"),
    PARENT_OTP("email.parent.otp.title"),
    EMAIL_CHANGE("email.change.title"),
    PROFILE_CHANGE("email.profile_change.title"),
    TENANT_API_KEY_GENERATED("email.tenant.api_key_generated.title"),
    TENANT_API_KEY_ROTATED("email.tenant.api_key_rotated.title"),
    TENANT_API_KEY_REVOKED("email.tenant.api_key_revoked.title"),
    TENANT_API_KEY_REACTIVATED("email.tenant.api_key_reactivated.title"),
    TENANT_WEBHOOK_SECRET_REGENERATED("email.tenant.webhook_secret_regenerated.title"),
    TENANT_STATUS_CHANGED("email.tenant.status_changed.title"),
    TENANT_CREATED("email.tenant.created.title");

    private final String subjectKey;
    EmailTemplate(final String subjectKey) {
        this.subjectKey = subjectKey;
    }

    public String subjectKey() {
        return subjectKey;
    }
}
