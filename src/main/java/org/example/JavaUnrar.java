package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public class JavaUnrar {

    private static final byte[] RAR5_SIGNATURE = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00};
    private static final int MAX_SFX_SIZE = 1024 * 1024; // 1MB

    public static void main(String[] args) throws IOException {
        Path archivePath = Path.of("downloads/test.rar");
        parseRarArchive(archivePath);
    }

    public static void parseRarArchive(Path archivePath) throws IOException {
        try (InputStream is = Files.newInputStream(archivePath)) {
            long signatureOffset = findSignature(is);
            if (signatureOffset == -1) {
                System.out.println("RAR 5.0 signature not found");
                return;
            }

            System.out.println("Found RAR 5.0 signature at offset: " + signatureOffset);

            // Read archive header
            ArchiveHeader header = readArchiveHeader(is);
            System.out.println("Archive Header: " + header);
        }
    }

    private static long findSignature(InputStream is) throws IOException {
        byte[] buffer = new byte[8];
        int bytesRead;
        long offset = 0;

        while (offset < MAX_SFX_SIZE && (bytesRead = is.read()) != -1) {
            buffer[(int)(offset % 8)] = (byte) bytesRead;

            if (offset >= 7) {
                boolean match = true;
                for (int i = 0; i < 8; i++) {
                    if (buffer[(int)((offset - 7 + i) % 8)] != RAR5_SIGNATURE[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return offset - 7;
                }
            }
            offset++;
        }
        return -1;
    }

    private static ArchiveHeader readArchiveHeader(InputStream is) throws IOException {
        long size = readVInt(is);
        long type = readVInt(is);
        long flags = readVInt(is);

        String name = null;
        if ((flags & 0x0001) != 0) {
            long nameLength = readVInt(is);
            byte[] nameBytes = is.readNBytes((int) nameLength);

            // Check if name is invalid (first byte is zero)
            if (nameBytes.length > 0 && nameBytes[0] != 0) {
                // Truncate at first trailing zero
                int actualLength = nameBytes.length;
                for (int i = 0; i < nameBytes.length; i++) {
                    if (nameBytes[i] == 0) {
                        actualLength = i;
                        break;
                    }
                }
                name = new String(nameBytes, 0, actualLength, StandardCharsets.UTF_8);
            }
        }

        Instant time = null;
        if ((flags & 0x0002) != 0) {
            boolean unixTime = (flags & 0x0004) != 0;
            boolean nanoseconds = (flags & 0x0008) != 0;

            if (unixTime) {
                if (nanoseconds) {
                    long nanos = readLong(is);
                    time = Instant.ofEpochSecond(0, nanos);
                } else {
                    int seconds = readInt(is);
                    time = Instant.ofEpochSecond(seconds);
                }
            } else {
                long fileTime = readLong(is);
                // Windows FILETIME: 100-nanosecond intervals since 1601-01-01
                long unixNanos = (fileTime - 116444736000000000L) * 100;
                time = Instant.ofEpochSecond(0, unixNanos);
            }
        }

        return new ArchiveHeader(size, type, flags, name, time);
    }

    private static long readVInt(InputStream is) throws IOException {
        long result = 0;
        int shift = 0;
        int b;

        do {
            b = is.read();
            if (b == -1) throw new IOException("Unexpected end of stream");
            result |= ((long) (b & 0x7F)) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        return result;
    }

    private static int readInt(InputStream is) throws IOException {
        byte[] bytes = is.readNBytes(4);
        return ((bytes[3] & 0xFF) << 24) | ((bytes[2] & 0xFF) << 16) |
                ((bytes[1] & 0xFF) << 8) | (bytes[0] & 0xFF);
    }

    private static long readLong(InputStream is) throws IOException {
        byte[] bytes = is.readNBytes(8);
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long) (bytes[i] & 0xFF)) << (i * 8);
        }
        return result;
    }

    static class ArchiveHeader {
        long size;
        long type;
        long flags;
        String name;
        Instant time;

        public ArchiveHeader(long size, long type, long flags, String name, Instant time) {
            this.size = size;
            this.type = type;
            this.flags = flags;
            this.name = name;
            this.time = time;
        }

        @Override
        public String toString() {
            return "ArchiveHeader{size=" + size + ", type=" + type + ", flags=0x" +
                    Long.toHexString(flags) + ", name='" + name + "', time=" + time + "}";
        }
    }
}