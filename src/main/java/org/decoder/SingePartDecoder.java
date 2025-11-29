package org.decoder;

import java.io.*;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Single-part yEnc decoder implementation.
 * Handles yEnc files that are not split into multiple parts.
 *
 * yEnc encoding formula: O = (I + 42) % 256
 * yEnc decoding formula: I = (O - 42) % 256
 *
 * Critical characters (ASCII 00h, 0Ah, 0Dh, 3Dh) are escaped with '=' (0x3D)
 * followed by the character value + 64 (mod 256)
 */
public class SingePartDecoder{

    private static final int ESCAPE_CHAR = 0x3D; // '='
    private static final int OFFSET = 42;
    private static final int ESCAPE_OFFSET = 64;
    private static final int BUFFER_SIZE = 8192;

    record YencHeader(String filename, long size, int line) {
        static YencHeader parse(String line) {
            // Parse =ybegin line (single-part has no part/total)
            return new YencHeader(
                    extractValue(line, "name"),
                    Long.parseLong(Objects.requireNonNull(extractValue(line, "size"))),
                    Integer.parseInt(Objects.requireNonNull(extractValue(line, "line")))
            );
        }
    }

    record YencTrailer(long size, String crc32) {
        static YencTrailer parse(String line) {
            // Parse =yend line (single-part has no part/pcrc32)
            return new YencTrailer(
                    Long.parseLong(Objects.requireNonNull(extractValue(line, "size"))),
                    extractValue(line, "crc32")
            );
        }
    }

    private static String extractValue(String line, String key) {
        String pattern = key + "=";
        int start = line.indexOf(pattern);
        if (start == -1) return null;

        start += pattern.length();
        int end = line.indexOf(' ', start);
        if (end == -1) end = line.length();

        return line.substring(start, end);
    }

    public void decode(Reader reader, OutputStream output) throws IOException {
        try (var bufferedReader = new BufferedReader(reader);
             var bufferedOutput = new BufferedOutputStream(output, BUFFER_SIZE)) {

            var crc = new CRC32();
            YencHeader header = null;
            var inYencData = false;

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                switch (line) {
                    case String s when s.startsWith("=ybegin") -> {
                        header = YencHeader.parse(s);
                        inYencData = true;
                    }
                    case String s when s.startsWith("=yend") -> {
                        var trailer = YencTrailer.parse(s);
                        validatePart(crc.getValue(), trailer, header);
                        bufferedOutput.flush();
                        return;
                    }
                    default -> {
                        if (inYencData) {
                            decodeLine(line, bufferedOutput, crc);
                        }
                    }
                }
            }
        }
    }

    private void validatePart(long actualCrc, YencTrailer trailer, YencHeader header) {
        if (trailer.crc32() != null) {
            var actualCrcHex = Long.toHexString(actualCrc);
            if (!actualCrcHex.equalsIgnoreCase(trailer.crc32())) {
                throw new RuntimeException("""
                    CRC mismatch:
                      Expected: %s
                      Got:      %s
                    """.formatted(trailer.crc32(), actualCrcHex));
            }
        }
    }

    /**
     * Decodes a single line of yEnc encoded data
     *
     * @param line the encoded line
     * @param outputStream the stream to write decoded bytes to
     * @param crc the CRC32 checksum calculator
     * @throws IOException if an I/O error occurs
     */
    private void decodeLine(String line, OutputStream outputStream, CRC32 crc) throws IOException {
        // Remove trailing whitespace (spaces, tabs, CR, LF)
        line = line.stripTrailing();

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
                decoded = (ch - ESCAPE_OFFSET - OFFSET + 256) % 256;
                escaped = false;
            } else {
                decoded = (ch - OFFSET + 256) % 256;
            }

            // Optional: skip NULL bytes (per yEnc spec)
            if (decoded == 0) {
                continue;
            }

            byte decodedByte = (byte) decoded;
            outputStream.write(decodedByte);
            crc.update(decodedByte);  // Update CRC
        }

        // Validate no orphaned escape character
        if (escaped) {
            throw new RuntimeException("Orphaned escape character at end of line");
        }
    }
}
