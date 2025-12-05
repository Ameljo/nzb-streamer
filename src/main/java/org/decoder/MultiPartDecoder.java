package org.decoder;

import java.io.*;
import java.util.Objects;
import java.util.zip.CRC32;

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
    private static final int BUFFER_SIZE = 8192;


    public record YencHeader(String filename, long size, int line, String part, String total) {
        static YencHeader parse(String line) {
            // Parse =ybegin line
            return new YencHeader(
                    extractValue(line, "name"),
                    Long.parseLong(extractValue(line, "size")),
                    Integer.parseInt(extractValue(line, "line")),
                    extractValue(line, "part"),
                    extractValue(line, "total")
            );
        }
    }

    public record YencPartInfo(long begin, long end, String pcrc32) {
        static YencPartInfo parse(String line) {
            return new YencPartInfo(
                    Long.parseLong(extractValue(line, "begin")),
                    Long.parseLong(extractValue(line, "end")),
                    extractValue(line, "pcrc32")
            );
        }
    }

    record YencTrailer(long size, String part, String pcrc32, String crc32) {
        static YencTrailer parse(String line) {
            return new YencTrailer(
                    Long.parseLong(Objects.requireNonNull(extractValue(line, "size"))),
                    extractValue(line, "part"),
                    extractValue(line, "pcrc32"),
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



    @Override
    public byte[] decode(Reader reader) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            var crc = new CRC32();
            YencHeader header = null;
            YencPartInfo partInfo = null;
            var inYencData = false;

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                switch (line) {
                    case String s when s.startsWith("=ybegin") -> {
                        header = YencHeader.parse(s);
                        inYencData = true;
                    }
                    case String s when s.startsWith("=ypart") -> {
                        partInfo = YencPartInfo.parse(s);
                    }
                    case String s when s.startsWith("=yend") -> {
                        var trailer = YencTrailer.parse(s);
                         validatePart(crc.getValue(), trailer, header);
                        return output.toByteArray();
                    }
                    default -> {
                        if (inYencData) {
                            output.write(decodeLine(line, crc));
                        }
                    }
                }
            }
        }

        return output.toByteArray();
    }

    @Override
    public YencPartInfo parseYencPartInfo(Reader reader) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if(line.startsWith("=ypart")) {
                    return YencPartInfo.parse(line);
                }
            }
        }

        return null;
    }

    @Override
    public YencHeader parseYencHeader(Reader reader) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

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


    private void validatePart(long actualCrc, YencTrailer trailer, YencHeader header) {
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
    /**
     * Decodes a single line of yEnc encoded data
     *
     * @param line the encoded line
     * @param outputStream the stream to write decoded bytes to
     * @throws IOException if an I/O error occurs
     */
    private byte[] decodeLine(String line, CRC32 crc) throws IOException {
        // Remove trailing whitespace (spaces, tabs, CR, LF)
//        line = line.stripTrailing();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

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
            outputStream.write(decodedByte);
            crc.update(decodedByte);  // Update CRC
        }

        // Validate no orphaned escape character
        if (escaped) {
            throw new RuntimeException("Orphaned escape character at end of line");
        }

        return outputStream.toByteArray();
    }
}
