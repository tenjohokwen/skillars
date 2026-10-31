package com.softropic.skillars.platform.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigurationActivateSecurityTest {

    @Test
    void disabledSecurityOnProd_failsStartup() {
        MockEnvironment env = new MockEnvironment().withProperty("activate.security", "false");
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> SecurityConfiguration.activateSecurity(env))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("activate.security");
    }

    @Test
    void disabledSecurityOnUat_failsStartup() {
        MockEnvironment env = new MockEnvironment().withProperty("activate.security", "false");
        env.setActiveProfiles("uat", "other");

        assertThatThrownBy(() -> SecurityConfiguration.activateSecurity(env))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledSecurityOnDevOrTest_isStillAllowed() {
        MockEnvironment env = new MockEnvironment().withProperty("activate.security", "false");
        env.setActiveProfiles("test");

        assertThat(SecurityConfiguration.activateSecurity(env)).isFalse();
    }

    @Test
    void enabledOrUnsetSecurityOnProd_isFine() {
        MockEnvironment unset = new MockEnvironment();
        unset.setActiveProfiles("prod");
        MockEnvironment on = new MockEnvironment().withProperty("activate.security", "true");
        on.setActiveProfiles("uat");

        assertThat(SecurityConfiguration.activateSecurity(unset)).isTrue();
        assertThat(SecurityConfiguration.activateSecurity(on)).isTrue();
    }
}
