package org.nzbstreamer.rar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a file of 61440 bytes. WinRAR divided this file into four volumes of 20 KB.
 *
 * <p>The parser reads each volume independently. A volume is a complete stream. A caller can have
 * only one volume at a given time. Each volume must give the part of the data that it holds. The
 * split flags must show if the file starts in the volume, continues in the volume, or stops in the
 * volume.</p>
 */
class RarMultiVolumeTest {

    private static final long TOTAL_SIZE = 61440;

    @Test
    @DisplayName("first volume: payload starts here and continues into the next")
    void firstVolume() throws IOException {
        byte[] archive = RarFixtures.bytes("rar5-vol.part1.rar");
        RarArchive parsed = RarFixtures.parse("rar5-vol.part1.rar");

        assertTrue(parsed.volume(), "should be flagged as part of a volume set");
        assertEquals(0, parsed.volumeNumber());
        assertTrue(parsed.firstVolume());

        RarFileEntry entry = RarFixtures.entryNamed(parsed, "bigfile.bin");
        assertFalse(entry.splitBefore(), "nothing precedes the first volume");
        assertTrue(entry.splitAfter(), "the payload continues into volume 2");
        assertEquals(TOTAL_SIZE, entry.unpackedSize(), "unpacked size is the whole file");
        assertTrue(entry.packedSize() < TOTAL_SIZE, "this volume holds only part of the payload");

        // Only the first volume holds the start of the file. Thus the marker is only in this volume.
        RarFixtures.assertPayloadStartsAt(archive, entry);
    }

    @Test
    @DisplayName("middle volumes: split on both sides")
    void middleVolumes() throws IOException {
        for (String fixture : List.of("rar5-vol.part2.rar", "rar5-vol.part3.rar")) {
            RarArchive parsed = RarFixtures.parse(fixture);
            RarFileEntry entry = RarFixtures.entryNamed(parsed, "bigfile.bin");

            assertTrue(entry.splitBefore(), fixture + " continues from the previous volume");
            assertTrue(entry.splitAfter(), fixture + " continues into the next volume");
            assertEquals(TOTAL_SIZE, entry.unpackedSize(), fixture);
        }
        assertEquals(1, RarFixtures.parse("rar5-vol.part2.rar").volumeNumber());
        assertEquals(2, RarFixtures.parse("rar5-vol.part3.rar").volumeNumber());
    }

    @Test
    @DisplayName("last volume: payload ends here")
    void lastVolume() throws IOException {
        RarArchive parsed = RarFixtures.parse("rar5-vol.part4.rar");
        assertEquals(3, parsed.volumeNumber());
        assertFalse(parsed.firstVolume());

        RarFileEntry entry = RarFixtures.entryNamed(parsed, "bigfile.bin");
        assertTrue(entry.splitBefore());
        assertFalse(entry.splitAfter(), "the file ends in the last volume");
    }

    @Test
    @DisplayName("the four volumes together account for the whole file, with no gaps or overlap")
    void volumesSumToWholeFile() throws IOException {
        long total = 0;
        for (String fixture : List.of("rar5-vol.part1.rar", "rar5-vol.part2.rar",
                "rar5-vol.part3.rar", "rar5-vol.part4.rar")) {
            total += RarFixtures.entryNamed(RarFixtures.parse(fixture), "bigfile.bin").packedSize();
        }
        assertEquals(TOTAL_SIZE, total,
                "packed sizes across the volume set should add up to the original file size");
    }
}
