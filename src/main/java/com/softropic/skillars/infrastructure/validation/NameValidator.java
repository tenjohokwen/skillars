package com.softropic.skillars.infrastructure.validation;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;


public class NameValidator implements ConstraintValidator<Name, String> {

    private static final Set<String> REJECTED_NAMES;

    private static final String VALID_NAME_REGEX = "^[a-zA-Z]+(([',. -][a-zA-Z ])?[a-zA-Z]*)*$";
    //^([a-zäöüßÄÖÜẞA-Z]+)[0-9]*\.*[a-zäöüßÄÖÜẞA-Z0-9]+$|^[a-zäöüßÄÖÜẞA-Z]+[0-9]*$

    static {
        final String[] rejectedNamesArray = {"abuse", "administrator", "autoconfig", "broadcasthost", "ftp", "hostmaster",
                "imap", "info", "is", "isatap", "it", "localdomain", "localhost", "mail", "mailer-daemon", "marketing",
                "mis", "news", "nobody", "noc", "noreply", "no-reply", "pop", "pop3", "postmaster", "root", "sales",
                "security", "smtp", "ssladmin", "ssladministrator", "sslwebmaster", "support", "sysadmin", "usenet",
                "uucp", "webmaster", "wpad", "www", "admin"};
        REJECTED_NAMES = Arrays.stream(rejectedNamesArray).collect(Collectors.toSet());
    }


    @Override
    public void initialize(Name constraintAnnotation) {
        ConstraintValidator.super.initialize(constraintAnnotation);
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return Optional.ofNullable(value)
                       .map(StringUtils::trim)
                       .filter(StringUtils::isNotBlank)
                       .filter(str -> !StringUtils.startsWithAny(str,
                                                                 new String[]{"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "-", "_"}))
                       .filter(str -> str.matches("[\\p{L}\\p{Nd}\\p{Nl}\\u002D\\p{Zs}]*"))
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
                                                                '_'))
                       .filter(str -> !REJECTED_NAMES.contains(str))
                       .filter(str -> str.length() < 50).isPresent();
    }
}
