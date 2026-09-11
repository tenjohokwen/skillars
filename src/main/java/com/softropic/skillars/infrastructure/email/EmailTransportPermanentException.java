package com.softropic.skillars.infrastructure.email;

/**
 * The send failed for a reason no retry can fix — a malformed recipient address, or the message
 * itself being rejected by the transport.
 */
public class EmailTransportPermanentException extends EmailTransportException {

    public EmailTransportPermanentException(String message, Throwable cause) {
        super(message, cause);
    }

    public EmailTransportPermanentException(String message) {
        super(message);
    }
}
