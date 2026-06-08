package com.softropic.skillars.infrastructure.validation;

import java.util.Set;

public final class LangUtil {

    private static final Set<String> SUPPORTED_LANGS = Set.of("en", "fr");

    private LangUtil() {}

    /**
     * This method's purpose is twofold. Ensures that only supported languages are used.
     * And any malicious value fed into the cookie will not be consumed if not found in the set.
     * @param lang ISO 639-1 Code
     * @return true if is supported else false
     */
    public static boolean isLangSupported(final String lang) {
        return SUPPORTED_LANGS.contains(lang);
    }

    public static String getSupportedLang(final String lang) {
        if (isLangSupported(lang)) {
            return lang;
        }
        return "en";
    }
}
