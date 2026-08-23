package org.nzbstreamer.decoder;

import org.nzbstreamer.decoder.records.YencHeader;
import org.nzbstreamer.decoder.records.YencTrailer;

import java.io.*;
import java.util.zip.CRC32;

/**
 * Abstract base class for yEnc decoders (single-part and multi-part).
 * Contains shared constants and utility methods.
 */
public abstract class AbstractYencDecoder implements YencDecoder {
    protected static final int ESCAPE_CHAR = 0x3D; // '='
    protected static final int OFFSET = 42;
    protected static final int ESCAPE_OFFSET = 64;

    @Override
    public YencHeader parseYencHeader(Reader reader) throws Exception {
        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if(line.startsWith("=ybegin")) {
                    return YencHeader.parse(line);
                }
            }
        }
        return null;
    }

    /**
     * Decodes a single line of yEnc encoded data
     *
     * @param line the encoded line
     * @param output the stream to write decoded bytes to
     * @throws IOException if an I/O error occurs
     */
    protected void decodeLine(String line, CRC32 crc, OutputStream output) throws IOException {
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
                decoded = (ch - ESCAPE_OFFSET - OFFSET) & 0xFF;
                escaped = false;
            } else {
                decoded = (ch - OFFSET) & 0xFF;
            }

            byte decodedByte = (byte) decoded;
            output.write(decodedByte);
            crc.update(decodedByte);  // Update CRC
        }

        // Validate no orphaned escape character
        if (escaped) {
            throw new RuntimeException("Orphaned escape character at end of line");
        }
    }

    protected void validatePart(long actualCrc, YencTrailer trailer) {
        if (trailer.pcrc32() != null) {
            var actualCrcHex =  String.format("%08x", actualCrc);;
            if (!actualCrcHex.equalsIgnoreCase(trailer.pcrc32())) {
                throw new RuntimeException("""
                    Part %s CRC mismatch:
                      Expected: %s
                      Got:      %s
                    """.formatted(trailer.part(), trailer.pcrc32(), actualCrcHex));
            }
        }
    }
}

