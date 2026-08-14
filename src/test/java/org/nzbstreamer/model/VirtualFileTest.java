package org.nzbstreamer.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.nzbstreamer.model.VirtualFileTestData.CHUNK_LENGTH;
import static org.nzbstreamer.model.VirtualFileTestData.OFFSET;
import static org.nzbstreamer.model.VirtualFileTestData.SEGMENT_SIZE;
import static org.nzbstreamer.model.VirtualFileTestData.fileOfTwoVolumes;
import static org.nzbstreamer.model.VirtualFileTestData.messageId;

/**
 * Tests the map of a file: a position of the file gives a segment and a position in that segment.
 */
class VirtualFileTest {

    @Test
    @DisplayName("the first byte of the file is after the headers of the volume")
    void firstByte() {
        VirtualFile.Location location = fileOfTwoVolumes().locate(0);

        assertEquals(messageId(1, 0), location.segment().getValue());
        assertEquals(OFFSET, location.byteInSegment(), "the bytes before it are the RAR header");
        assertEquals(SEGMENT_SIZE - OFFSET, location.bytesLeftInSegment());
        assertEquals("alt.binaries.test", location.group());
    }

    @Test
    @DisplayName("the last byte of a volume stops before the last blocks of the volume")
    void lastByteOfTheFirstVolume() {
        VirtualFile.Location location = fileOfTwoVolumes().locate(CHUNK_LENGTH - 1);

        assertEquals(messageId(1, 2), location.segment().getValue(), "the last segment of volume 1");
        assertEquals(1, location.bytesLeftInSegment(),
                "the bytes after it are the last blocks of the archive, thus they stay out");
    }

    @Test
    @DisplayName("the byte after the last byte of a volume is the first byte of the next volume")
    void firstByteOfTheSecondVolume() {
        VirtualFile.Location location = fileOfTwoVolumes().locate(CHUNK_LENGTH);

        assertEquals(messageId(2, 0), location.segment().getValue());
        assertEquals(OFFSET, location.byteInSegment(), "the headers of volume 2 stay out");
    }

    @Test
    @DisplayName("the file gives all its bytes and no more")
    void hasNext() {
        VirtualFile file = fileOfTwoVolumes();

        assertEquals(CHUNK_LENGTH * 2, file.getSize());
        assertTrue(file.hasNext(0));
        assertTrue(file.hasNext(file.getSize() - 1));
        assertFalse(file.hasNext(file.getSize()));
        assertFalse(file.hasNext(-1));
        assertThrows(IllegalArgumentException.class, () -> file.locate(file.getSize()));
    }

    @Test
    @DisplayName("a walk of the file uses each segment of each volume one time and in sequence")
    void walkUsesEverySegment() {
        VirtualFile file = fileOfTwoVolumes();

        long position = 0;
        String previous = null;
        int segments = 0;
        while (file.hasNext(position)) {
            VirtualFile.Location location = file.locate(position);
            if (!location.segment().getValue().equals(previous)) {
                previous = location.segment().getValue();
                segments++;
            }
            position += location.bytesLeftInSegment();
        }

        assertEquals(file.getSize(), position, "the walk must stop at the end of the file");
        assertEquals(6, segments, "the walk must use the three segments of the two volumes");
    }

    @Test
    @DisplayName("a file of one post has one chunk of all its bytes")
    void fileOfOnePost() {
        NzbFile post = VirtualFileTestData.volume(1);

        VirtualFile file = new VirtualFile(post.getSize(), "movie.mp3", post);

        assertEquals(post.getSize(), file.getSize());
        assertEquals(1, file.getChunks().size());
        assertEquals(3, file.segmentCount());
        assertEquals(messageId(1, 0), file.locate(0).segment().getValue());
        assertEquals(0, file.locate(0).byteInSegment(), "a post that is not an archive has no offset");
    }
}
