package com.softropic.skillars.infrastructure.validation;


import com.fasterxml.jackson.core.type.TypeReference;
import com.softropic.skillars.infrastructure.util.JsonUtil;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;

import java.util.Optional;
import java.util.Set;

/**
 * TODO see
 * https://www.owasp.org/index.php/Input_Validation_Cheat_Sheet
 * https://www.owasp.org/index.php/Bean_Validation_Cheat_Sheet
 * https://commons.apache.org/proper/commons-validator/apidocs/org/apache/commons/validator/GenericValidator.html
 * https://www.owasp.org/index.php/Parameter_Validation_Filter
 * https://github.com/mcasperson/ParameterValidationFilter
 * https://dzone.com/articles/xss-filter-java-ee-web-apps
 * http://abhisheksaxena29.blogspot.de/2014/12/input-validation-using-owasp-esapi.html
 * https://github.com/mcasperson/Common-utilities/tree/master/src/main/java/com/google/code/regexp
 *
 * Disposable email domains
 * https://disposable-emails.github.io/
 * https://opensourcelibs.com/lib/disposable-email-domain-list
 * https://github.com/disposable/disposable
 * https://www.block-disposable-email.com/cms/about/pricing/
 * https://www.disposable-email-detector.com/#tocSdisposabledomainmessage
 * https://verifier.meetchopra.com/
 */
public final class InputValidator {

    //"admin" is in use
    private static final Set<String>  REJECTED_NAMES;


    private InputValidator() {}

    static {

        //https://github.com/marteinn/The-Big-Username-Blocklist/blob/main/list.json
        TypeReference<Set<String>> typeReference = new TypeReference<>(){};
        REJECTED_NAMES = JsonUtil.fetchResourceAsObject("blacklisted-names", typeReference);
    }

    /*
    validation for first name should be like so
    0. is not blank StringUtils.isNotBlank(problemString) //conquer null
    1. trim problemString.trim()
    2. allow only letters, numbers, hyphens, apostrophe  problemString.matches("[\\p{L}\\p{Nd}\\p{Nl}\\u002D\u2032]*")
    3. Should not be made up of only hyphens  !problemString.matches("[\\u002D]*")
    4. Has a max of one blank space
    5. should not contain only numbers and hyphen
    6. Should not begin with number or hyphen
    7. Should not be in the list of reserved/rejected names
    8. Should not be more than 50 characters long
    see the hypen here http://www.fileformat.info/info/unicode/category/Pd/list.htm
    more unicode http://www.utf8-chartable.de/unicode-utf8-table.pl?start=65216&unicodeinhtml=dec
    https://www.regular-expressions.info/posixbrackets.html
    http://www.unicode.org/reports/tr18/
    https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/regex/Pattern.html
    https://www.regular-expressions.info/unicode.html#prop
    https://docs.oracle.com/javase/tutorial/essential/regex/unicode.html
    https://jrgraphix.net/research/unicode.php


 */

    // TODO this should be used in the registration process as well
    public static boolean isValidName(String problemString) {
        //see https://github.com/nomemory/java-bean-validation-extension
        //https://www.baeldung.com/vavr-validation-api
        return Optional.ofNullable(problemString)
                       .map(StringUtils::trim)
                       .filter(StringUtils::isNotBlank)
                       .filter(str -> !StringUtils.startsWithAny(str,
                                                                 "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "-", "_", " ", "'"))
                       .filter(str -> str.matches("[\\p{L}\\p{Nd}\\p{Nl}\\u002D\\u2032\\p{Zs}]*"))
                       .filter(str -> !str.matches("[\\u002D]*"))
                       .filter(str -> StringUtils.countMatches(str, " ") < 2)
                       .filter(str -> !StringUtils.containsOnly(str,
                                                                '0',
                                                                '1',
                                                                '2',
                                                                '3',
                                                                '4',
                                                                '5',
                                                                '6',
                                                                '7',
                                                                '8',
                                                                '9',
                                                                '-',
                                                                '_',
                                                                ' ',
                                                                '\''))
                       .filter(str -> !str.matches("[\\uFF01-\\uFFEE]*"))  //Halfwidth and Fullwidth Forms (CJK related)
                       .filter(str -> !REJECTED_NAMES.contains(str.toLowerCase()))
                       .filter(str -> str.length() < 50).isPresent();
    }


    //https://gist.github.com/mavieth/418b0ba7b3525517dd85b31ee881b2ec
    //https://email-verify.my-addr.com/list-of-most-popular-email-domains.php
    //https://gist.github.com/drakodev/e85c1fd6d9ac8634786d6139e0066fa0 (blacklist)
    //https://gist.github.com/fnando/192a2e708c022f74091c53b036191145
    //https://github.com/disposable/disposable?tab=readme-ov-file (disposable)
    public static boolean isValidEmail(String email) {
        //Also ensure this email does not have an illegal domain
        return EmailValidator.getInstance().isValid(email);
    }

}
