package org.nzbstreamer.rar;

import java.nio.charset.StandardCharsets;

/**
 * Decodes the name of a RAR4 file.
 *
 * <p>When the flag {@code 0x0200} is not set, the name field holds one byte for each character.</p>
 *
 * <p>When the flag is set, the name field holds three parts. The first part is the ASCII name. The
 * second part is one byte with the value {@code 0x00}. The third part is the Unicode name in a
 * compressed form. The compressed form starts with the high byte. Codes of two bits come after the
 * high byte. Each code gives one of four operations:</p>
 *
 * <ul>
 *   <li>use the next byte as the character;</li>
 *   <li>add the high byte to the next byte;</li>
 *   <li>use the next two bytes as one character of 16 bits;</li>
 *   <li>copy a sequence of characters from the ASCII name. A correction value can apply to each
 *       byte of the sequence.</li>
 * </ul>
 */
final class Rar4NameDecoder {

    private Rar4NameDecoder() {
    }

    static String decode(byte[] nameField, boolean unicode) {
        if (!unicode) {
            return new String(nameField, StandardCharsets.ISO_8859_1);
        }

        int separator = -1;
        for (int i = 0; i < nameField.length; i++) {
            if (nameField[i] == 0) {
                separator = i;
                break;
            }
        }
        if (separator < 0) {
            // The flag is set, but all the characters are ASCII. RAR then writes no compressed part.
            return new String(nameField, StandardCharsets.ISO_8859_1);
        }

        String asciiName = new String(nameField, 0, separator, StandardCharsets.ISO_8859_1);
        int encodedStart = separator + 1;
        if (encodedStart >= nameField.length) {
            return asciiName;
        }
        return decodeEncoded(nameField, separator, encodedStart);
    }

    private static String decodeEncoded(byte[] nameField, int asciiLength, int encodedStart) {
        StringBuilder decoded = new StringBuilder();
        int encodedPosition = encodedStart;
        int highByte = nameField[encodedPosition++] & 0xFF;
        int asciiPosition = 0;
        int flagBits = 0;
        int flags = 0;

        while (encodedPosition < nameField.length) {
            if (flagBits == 0) {
                flags = nameField[encodedPosition++] & 0xFF;
                flagBits = 8;
                if (encodedPosition >= nameField.length) {
                    break;
                }
            }
            flagBits -= 2;

            switch ((flags >> flagBits) & 0x03) {
                case 0 -> {
                    decoded.append((char) (nameField[encodedPosition++] & 0xFF));
                    asciiPosition++;
                }
                case 1 -> {
                    decoded.append((char) ((nameField[encodedPosition++] & 0xFF) + (highByte << 8)));
                    asciiPosition++;
                }
                case 2 -> {
                    if (encodedPosition + 1 >= nameField.length) {
                        return decoded.toString();
                    }
                    int low = nameField[encodedPosition++] & 0xFF;
                    int high = nameField[encodedPosition++] & 0xFF;
                    decoded.append((char) (low + (high << 8)));
                    asciiPosition++;
                }
                default -> {
                    int length = nameField[encodedPosition++] & 0xFF;
                    if ((length & 0x80) != 0) {
                        if (encodedPosition >= nameField.length) {
                            return decoded.toString();
                        }
                        int correction = nameField[encodedPosition++] & 0xFF;
                        for (length = (length & 0x7F) + 2;
                             length > 0 && asciiPosition < asciiLength;
                             length--, asciiPosition++) {
                            int corrected = ((nameField[asciiPosition] & 0xFF) + correction) & 0xFF;
                            decoded.append((char) (corrected | (highByte << 8)));
                        }
                    } else {
                        for (length += 2;
                             length > 0 && asciiPosition < asciiLength;
                             length--, asciiPosition++) {
                            decoded.append((char) (nameField[asciiPosition] & 0xFF));
                        }
                    }
                }
            }
        }
        return decoded.toString();
    }
}
