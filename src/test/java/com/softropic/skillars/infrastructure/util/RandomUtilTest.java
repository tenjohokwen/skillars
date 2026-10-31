package com.softropic.skillars.infrastructure.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RandomUtilTest {

    @Test
    void accountKeys_areUrlSafeWithAtLeast128BitsAndFitTheResetDtoLimit() {
        for (String key : new String[] {RandomUtil.generateActivationKey(), RandomUtil.generateResetKey()}) {
            assertThat(key).matches("[A-Za-z0-9_-]{22}"); // 16 bytes, unpadded URL-safe Base64
            assertThat(key.length()).isLessThanOrEqualTo(25);
        }
    }

    @Test
    void accountKeys_doNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(RandomUtil.generateResetKey())).isTrue();
        }
    }
}
