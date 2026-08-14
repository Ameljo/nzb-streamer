package org.nzbstreamer.rar;

import java.io.IOException;

/** The parser throws this exception when the data does not agree with the format of the signature. */
public class RarParseException extends IOException {

    public RarParseException(String message) {
        super(message);
    }

    public RarParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
