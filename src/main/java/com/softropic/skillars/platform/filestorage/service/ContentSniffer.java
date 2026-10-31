package com.softropic.skillars.platform.filestorage.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Magic-byte detection for the image and PDF types the upload allow-list accepts. The presigned PUT stores
 * whatever bytes the client sends under the Content-Type it declared, so {@code confirmUpload} checks the real
 * header instead of trusting that declaration.
 */
final class ContentSniffer {

    /** Bytes {@code confirmUpload} reads from the start of the object (enough for every signature below). */
    static final int HEADER_BYTES = 16;

    private ContentSniffer() {
    }

    /** The MIME type the leading bytes identify, if it is one of the checked types. */
    static Optional<String> sniff(byte[] head) {
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of("image/png");
        }
        String ascii = new String(head, StandardCharsets.ISO_8859_1);
        if (ascii.startsWith("GIF87a") || ascii.startsWith("GIF89a")) {
            return Optional.of("image/gif");
        }
        if (ascii.length() >= 12 && ascii.startsWith("RIFF") && ascii.startsWith("WEBP", 8)) {
            return Optional.of("image/webp");
        }
        if (ascii.startsWith("%PDF-")) {
            return Optional.of("application/pdf");
        }
        return Optional.empty();
    }

    /** True for a declared type whose content this class can verify (images and PDF). */
    static boolean isChecked(String declaredContentType) {
        return declaredContentType != null
            && (declaredContentType.toLowerCase().startsWith("image/")
                || "application/pdf".equalsIgnoreCase(declaredContentType));
    }

    /** True when {@code head} is the declared type; a type this class does not check always matches. */
    static boolean matches(String declaredContentType, byte[] head) {
        if (!isChecked(declaredContentType)) {
            return true;
        }
        return sniff(head).filter(declaredContentType::equalsIgnoreCase).isPresent();
    }

    private static boolean startsWith(byte[] b, int... expected) {
        if (b.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((b[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
