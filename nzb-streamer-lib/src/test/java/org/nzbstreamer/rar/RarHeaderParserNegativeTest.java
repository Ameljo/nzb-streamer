package org.nzbstreamer.rar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the parser with bad data. The parser must stop quickly and give a clear error. The parser
 * must not stop the program. The parser must not make a buffer with a length from a bad field.
 *
 * <p>Each test uses a part of a sample archive or a short sequence of bytes. No test makes RAR
 * data.</p>
 */
class RarHeaderParserNegativeTest {

    private static RarArchive parse(byte[] data) throws IOException {
        return new RarHeaderParser().parse(new ByteArrayInputStream(data));
    }

    /** Finds the first position of {@code needle} in {@code haystack} between from and to. */
    private static int indexOf(byte[] haystack, byte[] needle, int from, int to) {
        outer:
        for (int start = from; start <= to - needle.length; start++) {
            for (int i = 0; i < needle.length; i++) {
                if (haystack[start + i] != needle[i]) {
                    continue outer;
                }
            }
            return start;
        }
        return -1;
    }

    @Test
    @DisplayName("a PAR2 file named .rar is rejected as not a RAR archive")
    void par2IsNotRar() {
        byte[] par2 = "PAR2\0PKT........".getBytes(StandardCharsets.ISO_8859_1);

        RarParseException failure = assertThrows(RarParseException.class, () -> parse(par2));
        assertTrue(failure.getMessage().contains("Not a RAR archive"), failure.getMessage());
    }

    @Test
    @DisplayName("empty and too-short input are rejected, not treated as an empty archive")
    void emptyInput() {
        assertThrows(RarParseException.class, () -> parse(new byte[0]));
        assertThrows(RarParseException.class, () -> parse(new byte[]{0x52, 0x61, 0x72}));
    }

    @Test
    @DisplayName("random bytes are rejected")
    void garbageInput() {
        byte[] garbage = new byte[4096];
        new Random(20260814).nextBytes(garbage);

        assertThrows(RarParseException.class, () -> parse(garbage));
    }

    @Test
    @DisplayName("a stream cut inside a header ends the walk cleanly and says so")
    void truncatedInsideHeader() throws IOException {
        byte[] cut = Arrays.copyOf(RarFixtures.bytes("rar5-single.rar"), 40);

        RarArchive parsed = parse(cut);

        assertTrue(parsed.truncated(), "the archive should be reported as truncated");
        assertFalse(parsed.endOfArchive());
        assertEquals(0, parsed.entries().size(), "no complete file header was available");
    }

    @Test
    @DisplayName("a stream cut inside a payload keeps the headers already found")
    void truncatedInsidePayload() throws IOException {
        byte[] cut = Arrays.copyOf(RarFixtures.bytes("rar5-multi.rar"), 200);

        RarArchive parsed = parse(cut);

        assertTrue(parsed.truncated());
        assertEquals(1, parsed.entries().size(), "the first header is complete and should be kept");
        assertEquals("movie.mkv", parsed.entries().get(0).name());
    }

    @Test
    @DisplayName("a corrupt name length is refused instead of sizing a buffer from it")
    void corruptNameLengthIsRejected() throws IOException {
        byte[] data = RarFixtures.bytes("rar5-single.rar");
        RarFileEntry entry = RarFixtures.parse("rar5-single.rar").entries().get(0);

        // The extra area comes after the name field. Thus the name field does not stop at
        // dataOffset. This test finds the name field in the header. The size of the name is in the
        // vint before the name. The name has 9 bytes. Thus this vint is the single byte 0x09. The
        // test does a check of that byte first. If the test changes the wrong byte, it shows
        // nothing.
        int nameAt = indexOf(data, entry.name().getBytes(StandardCharsets.UTF_8),
                Math.toIntExact(entry.headerOffset()), Math.toIntExact(entry.dataOffset()));
        assertTrue(nameAt > 0, "could not find the name field inside the header");
        int nameSizeAt = nameAt - 1;
        assertEquals(entry.name().length(), data[nameSizeAt],
                "expected the name size vint immediately before the name");

        // The byte 0xFF sets the bit that shows that one more byte follows. Thus the length
        // continues into the name bytes. The length becomes larger than the header.
        data[nameSizeAt] = (byte) 0xFF;

        RarParseException failure = assertThrows(RarParseException.class, () -> parse(data));
        assertTrue(failure.getMessage().contains("name"), failure.getMessage());
    }
}
