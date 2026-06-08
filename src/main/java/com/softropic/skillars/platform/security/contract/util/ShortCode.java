package com.softropic.skillars.platform.security.contract.util;

import org.sqids.Sqids;

import java.util.List;
import java.util.Random;

public final class ShortCode {
    private static final Random RANDOM = new Random();

    private static final String SEED  = "ZG8K7aeb9hALF3OcTw5SNMQqC1oVJvtEsljDnIfx0zyH2rdRpmYUkP46guXiBW";
    public static final Sqids   SQ_ID = Sqids.builder().alphabet(SEED).build();

    private ShortCode() {}

    private static String shuffleSeed() {
        final char[] chars = SEED.toCharArray();
        char temp;
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
    }

    public static String generateSeed() {
        return shuffleSeed();
    }

    public static String shorten(Long target, String seed) {
        final Sqids sqid = Sqids.builder().alphabet(seed).build();
        return sqid.encode(List.of(target));
    }

    public static String shortenUsingDefault(Long positiveLong) {
        return SQ_ID.encode(List.of(positiveLong));
    }

    public static Long revertUsingDefault(String target) {
        return SQ_ID.decode(target).get(0);
    }

    public static String shortenInt(int target) {
        // -ve numbers are not allowed
        final long unsignedLong = Integer.toUnsignedLong(target);
        return SQ_ID.encode(List.of(unsignedLong));
    }

    public static boolean isSame(String sqId, int source) {
        return shortenInt(source).equals(sqId);
    }


}
