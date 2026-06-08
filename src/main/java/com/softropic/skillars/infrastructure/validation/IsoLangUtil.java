package com.softropic.skillars.infrastructure.validation;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class IsoLangUtil {
    private static final Set<String> ISO2_LANGUAGES = Set.of(Locale.getISOLanguages());
    private static final Set<String> ISO3_LANGUAGES;
    private static final Set<String> ISO3_COUNTRIES = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA3);
    private static final Set<String> ISO2_COUNTRIES = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2);

    static {
        ISO3_LANGUAGES = initIso3LangSet();
    }

    private IsoLangUtil() {
    }

    public static boolean isValidISO2Language(String s) {
        return s != null && ISO2_LANGUAGES.contains(s);
    }

    public static boolean isValidISO3Language(String s) {
        return s != null && ISO3_LANGUAGES.contains(s);
    }

    public static boolean isValidISO2Country(String s) {
        return s != null && ISO2_COUNTRIES.contains(s);
    }

    public static boolean isValidISO3Country(String s) {
        return s != null && ISO3_COUNTRIES.contains(s);
    }

    private static Set<String> initIso3LangSet() {
        Set<String> lang3 = new HashSet<>();
        for (String iso2Code : ISO2_LANGUAGES) {
            String table = IsoLangCodes.ISO_LANGUAGE_TABLE;
            int tableLength = table.length();
            int index;
            char c1 = iso2Code.charAt(0);
            char c2 = iso2Code.charAt(1);
            for (index = 0; index < tableLength; index += 5) {
                if (table.charAt(index) == c1
                        && table.charAt(index + 1) == c2) {
                    break;
                }
            }
            lang3.add(table.substring(index + 2, index + 5));
        }
        return Set.copyOf(lang3);
    }

}
