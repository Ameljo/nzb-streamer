package org.nzbstreamer.rar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the parser with the sample archives from WinRAR.
 */
class RarHeaderParserTest {

    @Test
    @DisplayName("single stored file: entry, offset and CRC all line up")
    void singleStoredFile() throws IOException {
        byte[] archive = RarFixtures.bytes("rar5-single.rar");
        RarArchive parsed = RarFixtures.parse("rar5-single.rar");

        assertEquals(RarFormat.RAR5, parsed.format());
        assertTrue(parsed.endOfArchive(), "the walk should reach the end-of-archive block");
        assertFalse(parsed.truncated());

        assertEquals(1, parsed.entries().size());
        RarFileEntry movie = parsed.entries().get(0);
        assertEquals("movie.mkv", movie.name());
        assertEquals(8192, movie.unpackedSize());
        assertEquals(8192, movie.packedSize());
        assertEquals(0, movie.method());
        assertTrue(movie.stored());
        assertTrue(movie.streamable());
        assertFalse(movie.directory());
        assertFalse(movie.encrypted());

        RarFixtures.assertPayloadStartsAt(archive, movie);
        RarFixtures.assertPayloadCrcMatches(archive, movie);

        List<RarBlockType> types = parsed.blocks().stream().map(RarBlock::type).toList();
        assertTrue(types.contains(RarBlockType.MAIN), "main header should be reported: " + types);
        assertTrue(types.contains(RarBlockType.END), "end block should be reported: " + types);
    }

    @Test
    @DisplayName("several files plus a directory: every entry found, in order, at the right offset")
    void multipleEntries() throws IOException {
        byte[] archive = RarFixtures.bytes("rar5-multi.rar");
        RarArchive parsed = RarFixtures.parse("rar5-multi.rar");

        assertEquals(List.of("movie.mkv", "movie.nfo", "subs/movie.srt", "subs"),
                parsed.entries().stream().map(RarFileEntry::name).toList());

        assertEquals(8192, RarFixtures.entryNamed(parsed, "movie.mkv").packedSize());
        assertEquals(1024, RarFixtures.entryNamed(parsed, "movie.nfo").packedSize());
        assertEquals(2023, RarFixtures.entryNamed(parsed, "subs/movie.srt").packedSize());

        for (RarFileEntry entry : parsed.entries()) {
            if (entry.directory()) {
                continue;
            }
            RarFixtures.assertPayloadStartsAt(archive, entry);
            RarFixtures.assertPayloadCrcMatches(archive, entry);
        }

        RarFileEntry directory = RarFixtures.entryNamed(parsed, "subs");
        assertTrue(directory.directory(), "subs should be flagged as a directory");
        assertEquals(0, directory.packedSize());
        assertFalse(directory.streamable(), "a directory is not streamable");

        assertEquals(List.of("QO"), parsed.serviceEntries().stream().map(RarFileEntry::name).toList(),
                "the quick-open index is a service block, not a file");
    }

    @Test
    @DisplayName("compressed entry is reported with its real method, not silently treated as stored")
    void compressedEntry() throws IOException {
        byte[] archive = RarFixtures.bytes("rar5-compressed.rar");
        RarArchive parsed = RarFixtures.parse("rar5-compressed.rar");

        RarFileEntry movie = RarFixtures.entryNamed(parsed, "movie.mkv");
        assertEquals(3, movie.method());
        assertFalse(movie.stored());
        assertFalse(movie.streamable(), "compressed payload cannot be streamed byte-for-byte");
        assertEquals(8192, movie.unpackedSize());
        assertEquals(48, movie.packedSize(), "m3 shrinks the filler payload to 48 bytes");

        // The other tests use the marker. Compressed data must not start with the marker. This
        // test thus shows that the marker check is effective.
        byte[] marker = RarFixtures.marker("movie.mkv");
        byte[] atOffset = new byte[marker.length];
        System.arraycopy(archive, Math.toIntExact(movie.dataOffset()), atOffset, 0, marker.length);
        assertNotEquals(new String(marker), new String(atOffset),
                "compressed payload should not be readable as the raw file");
    }

    @Test
    @DisplayName("archive comment is reported as a service block and never counted as a file")
    void archiveComment() throws IOException {
        RarArchive parsed = RarFixtures.parse("rar5-comment.rar");

        assertEquals(List.of("movie.nfo"), parsed.entries().stream().map(RarFileEntry::name).toList());
        assertEquals(List.of("CMT"), parsed.serviceEntries().stream().map(RarFileEntry::name).toList());
    }

    @Test
    @DisplayName("non-ASCII file name is decoded exactly")
    void unicodeName() throws IOException {
        byte[] archive = RarFixtures.bytes("rar5-unicode.rar");
        RarArchive parsed = RarFixtures.parse("rar5-unicode.rar");

        assertEquals(1, parsed.entries().size());
        RarFileEntry entry = parsed.entries().get(0);
        assertEquals("film-café-日本.mkv", entry.name());
        assertEquals(3030, entry.packedSize());
        RarFixtures.assertPayloadStartsAt(archive, entry);
        RarFixtures.assertPayloadCrcMatches(archive, entry);
    }

    @Test
    @DisplayName("headers are found without reading the archive")
    void readsOnlyHeaderBytes() throws IOException {
        RarArchive parsed = RarFixtures.parse("rar5-multi.rar");
        long fixtureSize = RarFixtures.bytes("rar5-multi.rar").length;

        assertTrue(parsed.bytesRead() < 1024,
                "should read only header bytes, but read " + parsed.bytesRead());
        assertTrue(parsed.bytesSkipped() > fixtureSize / 2,
                "payloads should be skipped, not read; skipped " + parsed.bytesSkipped());
    }
}
