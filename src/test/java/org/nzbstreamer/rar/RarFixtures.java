package org.nzbstreamer.rar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reads the sample archives and does a check of the offsets that the parser gives.
 *
 * <p>WinRAR made the sample archives. Refer to {@code src/test/resources/rar/README.md}. This
 * class does not make RAR data. Each file in a sample starts with the marker
 * {@code PAYLOAD:<name>:}. Thus a test can do a check of the {@code dataOffset} value with the
 * content of the file and with the CRC32 value from WinRAR. The parser does not supply these two
 * items of data.</p>
 */
final class RarFixtures {

    private RarFixtures() {
    }

    static byte[] bytes(String fixture) throws IOException {
        try (InputStream in = RarFixtures.class.getResourceAsStream("/rar/" + fixture)) {
            assertNotNull(in, "missing test fixture /rar/" + fixture);
            return in.readAllBytes();
        }
    }

    static RarArchive parse(String fixture) throws IOException {
        return new RarHeaderParser().parse(new ByteArrayInputStream(bytes(fixture)));
    }

    /** The marker at the start of each file in a sample archive. */
    static byte[] marker(String entryName) {
        return ("PAYLOAD:" + entryName + ":").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Does a check that the data of the entry starts at the offset that the parser gives. The test
     * compares the bytes at that offset with the marker of the initial file.
     */
    static void assertPayloadStartsAt(byte[] archive, RarFileEntry entry) {
        byte[] expected = marker(entry.name());
        int from = Math.toIntExact(entry.dataOffset());
        byte[] actual = Arrays.copyOfRange(archive, from, from + expected.length);
        assertArrayEquals(expected, actual,
                "payload of " + entry.name() + " should start at offset " + entry.dataOffset()
                        + " but found: " + new String(actual, StandardCharsets.UTF_8));
    }

    /**
     * Does a check that the data at the offset gives the CRC32 value from the archiver. An error of
     * one byte makes this check fail. Thus the check applies to the offset and to the length.
     */
    static void assertPayloadCrcMatches(byte[] archive, RarFileEntry entry) {
        assertNotNull(entry.crc32(), "fixture entry " + entry.name() + " has no stored CRC32");
        CRC32 crc = new CRC32();
        crc.update(archive, Math.toIntExact(entry.dataOffset()), Math.toIntExact(entry.packedSize()));
        assertEquals(String.format("%08X", entry.crc32()), String.format("%08X", crc.getValue()),
                "CRC32 of the bytes at dataOffset " + entry.dataOffset() + " should match the CRC32"
                        + " stored in the header of " + entry.name());
    }

    static RarFileEntry entryNamed(RarArchive archive, String name) {
        return archive.entries().stream()
                .filter(entry -> entry.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry named " + name + " in " + archive.entries()
                        .stream().map(RarFileEntry::name).toList()));
    }
}
