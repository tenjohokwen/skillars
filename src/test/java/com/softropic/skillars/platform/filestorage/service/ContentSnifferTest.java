package com.softropic.skillars.platform.filestorage.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSnifferTest {

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void recognisesEachCheckedType() {
        assertThat(ContentSniffer.sniff(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})).contains("image/jpeg");
        assertThat(ContentSniffer.sniff(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0})).contains("image/png");
        assertThat(ContentSniffer.sniff(ascii("GIF89a\u0001\u0000"))).contains("image/gif");
        assertThat(ContentSniffer.sniff(ascii("RIFF\u0001\u0002\u0003\u0004WEBPVP8 "))).contains("image/webp");
        assertThat(ContentSniffer.sniff(ascii("%PDF-1.7\n"))).contains("application/pdf");
    }

    @Test
    void declaredTypeMustMatchTheSniffedType() {
        assertThat(ContentSniffer.matches("application/pdf", ascii("%PDF-1.4"))).isTrue();
        assertThat(ContentSniffer.matches("image/png", ascii("%PDF-1.4"))).isFalse();
        assertThat(ContentSniffer.matches("image/jpeg", ascii("<svg onload=alert(1)>"))).isFalse();
        assertThat(ContentSniffer.matches("image/png", new byte[0])).isFalse();
        assertThat(ContentSniffer.matches("image/webp", ascii("RIFF....WAVEfmt "))).isFalse();
    }

    @Test
    void typesWithoutASignatureAreNotChecked() {
        assertThat(ContentSniffer.isChecked("text/plain")).isFalse();
        assertThat(ContentSniffer.matches("text/plain", new byte[]{1, 2, 3})).isTrue();
    }
}
