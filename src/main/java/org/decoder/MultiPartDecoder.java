package org.decoder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;

/**
 * Multi-part yEnc decoder implementation.
 * Handles yEnc files that are split into multiple parts (=ypart).
 *
 * yEnc encoding formula: O = (I + 42) % 256
 * yEnc decoding formula: I = (O - 42) % 256
 *
 * Critical characters (ASCII 00h, 0Ah, 0Dh, 3Dh) are escaped with '=' (0x3D)
 * followed by the character value + 64 (mod 256)
 */
public class MultiPartDecoder implements YencDecoder{

    private static final int ESCAPE_CHAR = 0x3D; // '='
    private static final int OFFSET = 42;
    private static final int ESCAPE_OFFSET = 64;

    @Override
    public OutputStream decode(Reader reader) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            String line;
            boolean inYencData = false;

            while ((line = bufferedReader.readLine()) != null) {
                // Check for yEnc header line (=ybegin)
                if (line.startsWith("=ybegin")) {
                    inYencData = true;
                    continue;
                }

                // Check for yEnc part header (=ypart) - skip it
                if (line.startsWith("=ypart")) {
                    continue;
                }

                // Check for yEnc trailer (=yend)
                if (line.startsWith("=yend")) {
                    break;
                }

                // Skip lines before yEnc data starts
                if (!inYencData) {
                    continue;
                }

                // Decode the line
                decodeLine(line, outputStream);
            }

        } catch (IOException e) {
            throw new RuntimeException("Error decoding yEnc data", e);
        }

        return outputStream;
    }

    /**
     * Decodes a single line of yEnc encoded data
     *
     * @param line the encoded line
     * @param outputStream the stream to write decoded bytes to
     * @throws IOException if an I/O error occurs
     */
    private void decodeLine(String line, OutputStream outputStream) throws IOException {
        boolean escaped = false;

        for (int i = 0; i < line.length(); i++) {
            int ch = line.charAt(i) & 0xFF;

            // Handle escape sequences
            if (ch == ESCAPE_CHAR && !escaped) {
                escaped = true;
                continue;
            }

            // Decode the character
            int decoded;
            if (escaped) {
                // Escaped character: subtract both the escape offset and the base offset
                decoded = (ch - ESCAPE_OFFSET - OFFSET + 256) % 256;
                escaped = false;
            } else {
                // Normal character: just subtract the base offset
                decoded = (ch - OFFSET + 256) % 256;
            }

            outputStream.write(decoded);
        }
    }
}
