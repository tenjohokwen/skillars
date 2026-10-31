package com.softropic.skillars.infrastructure.sanitizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** S-04: the sanitizer must catch the common ways of dodging a plain email / long-digit-run match. */
class ContactDetailSanitizerBypassTest {

    private final ContactDetailSanitizer sanitizer = new ContactDetailSanitizer();

    @ParameterizedTest
    @ValueSource(strings = {
        // phone numbers split into short groups
        "call me on 0791 112 3456",
        "0791-112-3456",
        "555-123-4567",
        "(020) 7946 0958",
        "+1 212 555 0100",
        "07911 123456",
        "text 0791.112.3456 please",
        // full-width digits and zero-width padding
        "０７９１１１２３４５６",
        "0791​112​3456",
        // spelled-out digits, alone and mixed with numerals
        "oh seven nine one one one two three four five six",
        "zero seven nine one 1 1 2 3 4 5 6",
        "o seven double nine one two three four five",
        // obfuscated email
        "john at gmail dot com",
        "john [at] gmail [dot] com",
        "john(at)gmail.com",
        "j.smith AT outlook DOT co DOT uk",
        // links and invite hosts
        "https://wa.me/447911123456",
        "wa.me/447911123456",
        "see www.my-coaching.example",
        "my site is coachjohn.com/book",
        "join t.me/coachjohn",
        "fc-berlin.de",
        // app handles
        "whatsapp: coachjohn",
        "my insta is coach_john",
        "add me on snap jsmith99",
        "snapchat = john.smith",
        "telegram @coachjohn",
        "dm me @coach_john",
        "@coachjohn"
    })
    void contactDetails_areRedacted(String text) {
        var result = sanitizer.sanitize(text);
        assertThat(result.wasModified()).as(text).isTrue();
        assertThat(result.sanitized()).as(text).contains("[contact details removed]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "See you at 5 pm",
        "Session at 5pm on Saturday",
        "We won 3-2 last week",
        "Final score was 10-0",
        "Ages 8-14 welcome",
        "U10 and U12 groups, 8-12 players",
        "Available Mon-Fri 09.00-17.00",
        "Sessions 17.00-18.30 and 18.30-20.00",
        "Seasons 2019 2020 2021",
        "available 2020-2026",
        "Licence 2023-04-15-001",
        "Reference ID: 100-200-300",
        "Booked for 2026-11-03 at 10:30",
        "Price is 45 euro for 60 minutes",
        "50 euro per hour, 200 for a pack of 5",
        "Pack of 10 sessions, 9 10 11 12 13 14 15",
        "Meet me at the gym",
        "I am at school until 3, then at the park",
        "Arrive at school. It starts at four",
        "one to one and five a side coaching",
        "Message me on WhatsApp please",
        "Find me on Instagram",
        "Please add me on snap",
        "Great coach, e.g. very patient.",
        "I am a certified football coach based in Berlin.",
        "Email me through the platform",
        "Thanks, see you there @ 5"
    })
    void normalText_isLeftAlone(String text) {
        var result = sanitizer.sanitize(text);
        assertThat(result.sanitized()).as(text).isEqualTo(text);
        assertThat(result.wasModified()).as(text).isFalse();
    }

    @Test
    void normalisationAlone_doesNotFlagCleanText() {
        // A ligature NFKC would rewrite; nothing to redact, so the original is returned verbatim.
        String text = "oﬃce hours";
        var result = sanitizer.sanitize(text);
        assertThat(result.wasModified()).isFalse();
        assertThat(result.sanitized()).isEqualTo(text);
    }

    @Test
    void redaction_keepsSurroundingText() {
        var result = sanitizer.sanitize("Hi, call 0791 112 3456 tomorrow at 5 pm");
        assertThat(result.sanitized()).isEqualTo("Hi, call [contact details removed] tomorrow at 5 pm");
    }
}
