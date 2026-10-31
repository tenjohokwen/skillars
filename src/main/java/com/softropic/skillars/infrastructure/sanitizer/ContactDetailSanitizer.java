package com.softropic.skillars.infrastructure.sanitizer;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts off-platform contact details (email, phone, links, messaging-app handles) from free text.
 * Deterministic and regex-based on purpose: it is the first line of defence for children's safety on
 * the chat and public-profile surfaces, so it errs on the side of redacting, but is tuned to leave
 * times ("5 pm", "09.00-17.00"), scores ("3-2"), ages ("8-14"), year ranges and prices alone.
 *
 * <p>Detection runs on an NFKC-normalised copy with invisible format characters (zero-width spaces and
 * joiners, soft hyphens) removed, so full-width digits / "@" and zero-width padding do not hide a
 * number. When nothing is redacted the original string is returned untouched.
 */
@Component
public class ContactDetailSanitizer {

    private static final String REDACTION = "[contact details removed]";

    private static final String TLDS =
        "com|net|org|info|biz|edu|gov|io|co|uk|de|fr|ch|nl|eu|xyz|app|dev|online|site|link|club";

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    // "john at gmail dot com", "john [at] gmail [dot] com", "john(at)gmail.com". A bare " at " is only an
    // "@" when the domain part is spelled with an explicit dot word / bracketed dot / real dot and ends in a
    // known TLD, so "meet me at the gym" and "arrive at school. It starts" are left alone.
    private static final String AT = "(?:\\s*[\\[({]\\s*at\\s*[\\])}]\\s*|\\s+at\\s+)";
    private static final String DOT = "(?:\\s*[\\[({]\\s*(?:dot|\\.)\\s*[\\])}]\\s*|\\s+dot\\s+|\\.)";
    private static final Pattern OBFUSCATED_EMAIL_PATTERN = Pattern.compile(
        "(?i)\\b[\\w.+\\-]+" + AT + "[\\w\\-]+(?:" + DOT + "[\\w\\-]+)*?" + DOT + "(?:" + TLDS + ")\\b");

    // Explicit links, short-link / chat-invite hosts, and bare domains on a conservative TLD list
    // ("fc-berlin.de", "mysite.com/coach"). Short TLDs that are also English words (it, me, at, in, is)
    // are only caught behind an explicit scheme or "www.".
    private static final Pattern URL_PATTERN = Pattern.compile(
        "(?i)(?:\\b(?:https?://|www\\.)\\S+"
            + "|\\b(?:wa\\.me|t\\.me|bit\\.ly|tinyurl\\.com|linktr\\.ee|discord\\.gg|ig\\.me|snapchat\\.com)/?\\S*"
            + "|\\b[a-z0-9][a-z0-9\\-]*(?:\\.[a-z0-9][a-z0-9\\-]*)*\\.(?:" + TLDS + ")\\b(?:/\\S*)?)");

    // Messaging apps. Two shapes: an explicit separator ("insta: john_smith", "whatsapp = ...", "snap@x")
    // or a handle-looking token (digit / underscore, 4+ chars) after the app name and optional filler
    // ("my insta is john_smith", "add me on snap jsmith99"). Plain prose such as "message me on whatsapp
    // please" does not match either.
    private static final String APPS =
        "whats\\s?app|snap(?:chat)?|insta(?:gram)?|telegram|tiktok|discord|skype|viber|messenger|facebook";
    private static final Pattern APP_HANDLE_EXPLICIT = Pattern.compile(
        "(?i)\\b(?:" + APPS + ")\\b[^\\S\\n]*[:=@][^\\S\\n]*@?[\\w.]{3,}");
    private static final Pattern APP_HANDLE_LOOSE = Pattern.compile(
        "(?i)\\b(?:" + APPS + ")\\b(?:[^\\S\\n]+(?:is|handle|username|user|id|name|me|on|at|-))*"
            + "[^\\S\\n]+@?(?=[\\w.]*[\\d_])[\\w.]{4,}");
    private static final Pattern BARE_HANDLE = Pattern.compile("(?<![\\w.@])@[A-Za-z0-9_.]{3,}");

    // Digits spelled out ("oh seven nine one ..."), possibly mixed with numerals. 7+ consecutive tokens
    // incl. at least one word is a number being dodged around digit matching; "one to one" and "five a side"
    // are nowhere near that long.
    private static final String DIGIT_TOKEN = "(?:\\d|zero|oh|one|two|three|four|five|six|seven|eight|nine|double|triple)";
    private static final Pattern SPELLED_DIGITS = Pattern.compile(
        "(?i)\\b(?:" + DIGIT_TOKEN + "(?:[\\s,.\\-]+|\\b)){7,}");
    private static final Pattern DIGIT_WORD = Pattern.compile("(?i)[a-z]");

    // Digit groups joined by up to three separator characters. Whether a candidate IS a phone number is
    // decided in looksLikePhone, not by the regex.
    private static final Pattern PHONE_CANDIDATE =
        Pattern.compile("(?<![\\d])\\+?\\(?\\d(?:[\\s\\-.()/]{0,3}\\d){6,17}(?![\\d])");
    private static final Pattern DIGIT_RUN_5 = Pattern.compile("\\d{5,}");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern GROUP_SPLIT = Pattern.compile("[^\\d]+");

    public SanitizerResult sanitize(String input) {
        if (input == null) return new SanitizerResult(null, false);
        String work = Normalizer.normalize(input, Normalizer.Form.NFKC).replaceAll("[\\p{Cf}]", "");

        String result = work;
        result = OBFUSCATED_EMAIL_PATTERN.matcher(result).replaceAll(REDACTION);
        result = EMAIL_PATTERN.matcher(result).replaceAll(REDACTION);
        result = URL_PATTERN.matcher(result).replaceAll(REDACTION);
        result = APP_HANDLE_EXPLICIT.matcher(result).replaceAll(REDACTION);
        result = APP_HANDLE_LOOSE.matcher(result).replaceAll(REDACTION);
        result = BARE_HANDLE.matcher(result).replaceAll(REDACTION);
        result = redactSpelledDigits(result);
        result = redactPhoneLikeSequences(result);

        // Modified only if something was actually redacted: normalisation alone must not flag clean text.
        boolean redacted = !result.equals(work);
        return redacted ? new SanitizerResult(result, true) : new SanitizerResult(input, false);
    }

    private String redactSpelledDigits(String input) {
        Matcher m = SPELLED_DIGITS.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String candidate = m.group();
            // A pure run of numerals is the phone matcher's business (it has the date/time exemptions).
            String replacement = DIGIT_WORD.matcher(candidate).find() ? REDACTION : candidate;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String redactPhoneLikeSequences(String input) {
        Matcher m = PHONE_CANDIDATE.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String candidate = m.group();
            String replacement = looksLikePhone(candidate) ? REDACTION : candidate;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * A candidate has 7+ digits (guaranteed by the regex). It is a phone number when:
     * <ul>
     *   <li>it starts with "+" or contains a bracketed area code — unambiguous;</li>
     *   <li>it contains an unbroken run of 5+ digits (a subscriber block), as before;</li>
     *   <li>its groups are space-separated and at least one group has 3+ digits ("0791 112 3456",
     *       "+44 7911 123456") — so "9 10 11 12 13 14 15" style lists and "2019 2020 2021" season lists
     *       are not caught;</li>
     *   <li>it is dash/dot/slash-separated with 10+ digits and is not an ISO date ("555-123-4567").</li>
     * </ul>
     * Year ranges ("2020-2026"), time ranges ("09.00-17.00"), licence / reference numbers
     * ("2023-04-15-001", "100-200-300") fall through every rule and stay.
     */
    private boolean looksLikePhone(String candidate) {
        int digits = (int) candidate.chars().filter(Character::isDigit).count();
        if (candidate.startsWith("+") || candidate.indexOf('(') >= 0) {
            return true;
        }
        if (DIGIT_RUN_5.matcher(candidate).find()) {
            return true;
        }
        if (ISO_DATE.matcher(candidate).find()) {
            return false;
        }
        boolean hasWhitespace = candidate.chars().anyMatch(Character::isWhitespace);
        int maxGroup = 0;
        boolean allYears = true;
        for (String group : GROUP_SPLIT.split(candidate)) {
            maxGroup = Math.max(maxGroup, group.length());
            allYears &= group.length() == 4 && (group.startsWith("19") || group.startsWith("20"));
        }
        if (hasWhitespace) {
            // "2019 2020 2021": a list of seasons, not a number.
            return maxGroup >= 3 && !allYears;
        }
        return digits >= 10;
    }

    public record SanitizerResult(String sanitized, boolean wasModified) {}
}
