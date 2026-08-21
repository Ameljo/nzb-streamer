package org.nzbstreamer.streams;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.VirtualFileTestData;
import org.nzbstreamer.service.SegmentFetcher;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.nzbstreamer.model.VirtualFileTestData.CHUNK_LENGTH;
import static org.nzbstreamer.model.VirtualFileTestData.expectedBytes;
import static org.nzbstreamer.model.VirtualFileTestData.fileOfTwoVolumes;

/**
 * Tests the stream with the segments of {@link VirtualFileTestData}. The test gives the bytes of
 * the segments, thus it needs no news server.
 */
class VirtualFileStreamTest {

    /** A fetcher that gives the segments of the test and counts the downloads. */
    private static final class TestSegments extends SegmentFetcher {

        private final Set<String> downloaded = Collections.synchronizedSet(new HashSet<>());

        TestSegments() {
            super(null);
        }

        @Override
        public byte[] fetch(String messageId, String group) {
            downloaded.add(messageId);
            return VirtualFileTestData.segmentBytes(messageId);
        }
    }

    @Test
    @DisplayName("a read of all the file gives the bytes of the file and no other byte")
    void readsTheWholeFile() throws IOException {
        TestSegments segments = new TestSegments();
        byte[] actual;

        VirtualFile file = fileOfTwoVolumes();
        try (VirtualFileStream stream = new VirtualFileStream(file, new PrefetchingSource(file, segments))) {
            actual = stream.readAllBytes();
        }

        assertArrayEquals(expectedBytes(), actual,
                "the stream must give the bytes of the file, without the bytes of the archive");
        assertEquals(6, segments.downloaded.size(), "the file uses the six segments one time");
    }

    @Test
    @DisplayName("the stream goes from one volume to the next volume without an action of the caller")
    void crossesTheBoundaryOfTheVolumes() throws IOException {
        byte[] expected = expectedBytes();

        VirtualFile file = fileOfTwoVolumes();
        try (VirtualFileStream stream =
                     new VirtualFileStream(file, new PrefetchingSource(file, new TestSegments()))) {
            stream.seek(CHUNK_LENGTH - 2);

            assertEquals(expected[(int) CHUNK_LENGTH - 2] & 0xFF, stream.read());
            assertEquals(expected[(int) CHUNK_LENGTH - 1] & 0xFF, stream.read());
            assertEquals(expected[(int) CHUNK_LENGTH] & 0xFF, stream.read(),
                    "this byte is the first byte of volume 2");
        }
    }

    @Test
    @DisplayName("a move of the cursor downloads nothing")
    void seekDownloadsNothing() throws IOException {
        TestSegments segments = new TestSegments();

        VirtualFile file = fileOfTwoVolumes();
        try (VirtualFileStream stream = new VirtualFileStream(file, new PrefetchingSource(file, segments))) {
            stream.seek(40);
            stream.skip(5);
            assertEquals(0, segments.downloaded.size(), "a move must not download a segment");

            assertEquals(expectedBytes()[45] & 0xFF, stream.read());
            assertEquals(1, segments.downloaded.size(), "the read operation downloads one segment");
        }
    }

    @Test
    @DisplayName("a read after a move gives the bytes of the new position")
    void readAfterSeek() throws IOException {
        byte[] expected = expectedBytes();

        VirtualFile file = fileOfTwoVolumes();
        try (VirtualFileStream stream =
                     new VirtualFileStream(file, new PrefetchingSource(file, new TestSegments()))) {
            stream.seek(30);
            byte[] actual = stream.readNBytes(10);

            byte[] wanted = new byte[10];
            System.arraycopy(expected, 30, wanted, 0, 10);
            assertArrayEquals(wanted, actual);
        }
    }

    @Test
    @DisplayName("mark and reset move the cursor to the same position")
    void markAndReset() throws IOException {
        VirtualFile file = fileOfTwoVolumes();
        try (VirtualFileStream stream =
                     new VirtualFileStream(file, new PrefetchingSource(file, new TestSegments()))) {
            stream.mark(100);
            byte[] first = stream.readNBytes(20);
            stream.reset();
            byte[] second = stream.readNBytes(20);

            assertArrayEquals(first, second, "Tika needs mark and reset, thus it adds no buffer");
        }
    }

    @Test
    @DisplayName("the stream stops at the end of the file")
    void stopsAtTheEnd() throws IOException {
        VirtualFile file = fileOfTwoVolumes();
        try (VirtualFileStream stream =
                     new VirtualFileStream(file, new PrefetchingSource(file, new TestSegments()))) {
            assertEquals(CHUNK_LENGTH * 2, stream.readAllBytes().length);
            assertEquals(-1, stream.read());
        }
    }
}
