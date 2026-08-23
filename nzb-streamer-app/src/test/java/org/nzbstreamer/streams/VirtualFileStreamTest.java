package org.nzbstreamer.streams;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.VirtualFileTestData;
import org.nzbstreamer.service.SegmentFetcher;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;

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

    /** A fetcher for the streaming decode path: gives the segment bytes straight to the queue. */
    private static final class TestChunkedSegments extends SegmentFetcher {

        TestChunkedSegments() {
            super(null);
        }

        /** Forces multiple small windows per segment, regardless of the real bufferSize argument. */
        private static final int TEST_CHUNK_SIZE = 3;

        @Override
        public void fetch(String messageId, String group, BlockingQueue<byte[]> buffer,
                          int bufferSize, int skip, int trim) throws InterruptedException {
            byte[] bytes = VirtualFileTestData.segmentBytes(messageId);
            int end = Math.min(skip + trim, bytes.length);
            byte[] used = skip < end ? Arrays.copyOfRange(bytes, skip, end) : new byte[0];
            // Splits into small chunks, like MultiPartDecoder.decode(...) really does with a real
            // bufferSize: a window here is smaller than a segment.
            for (int i = 0; i < used.length; i += TEST_CHUNK_SIZE) {
                int chunkEnd = Math.min(i + TEST_CHUNK_SIZE, used.length);
                buffer.put(Arrays.copyOfRange(used, i, chunkEnd));
            }
        }
    }

    @Test
    @Timeout(5)
    @DisplayName("a seek after the worker already ran restarts it at the new position (StreamingSource)")
    void seekAfterReadingRestartsWorker_streamingSource() throws IOException {
        byte[] expected = expectedBytes();

        VirtualFile file = fileOfTwoVolumes();
        try (VirtualFileStream stream =
                     new VirtualFileStream(file, new StreamingSource(file, new TestChunkedSegments()))) {
            byte[] first = stream.readNBytes(20);
            byte[] wantedFirst = new byte[20];
            System.arraycopy(expected, 0, wantedFirst, 0, 20);
            assertArrayEquals(wantedFirst, first);

            stream.seek(5);
            byte[] second = stream.readNBytes(10);
            byte[] wantedSecond = new byte[10];
            System.arraycopy(expected, 5, wantedSecond, 0, 10);
            assertArrayEquals(wantedSecond, second);
        }
    }

    @Test
    @Timeout(5)
    @DisplayName("a seek after the worker already ran restarts it at the new position")
    void seekAfterReadingRestartsWorker() throws IOException {
        byte[] expected = expectedBytes();

        VirtualFile file = fileOfTwoVolumes();
        try (VirtualFileStream stream =
                     new VirtualFileStream(file, new PrefetchingSource(file, new TestSegments()))) {
            byte[] first = stream.readNBytes(20);
            byte[] wantedFirst = new byte[20];
            System.arraycopy(expected, 0, wantedFirst, 0, 20);
            assertArrayEquals(wantedFirst, first);

            stream.seek(5);
            byte[] second = stream.readNBytes(10);
            byte[] wantedSecond = new byte[10];
            System.arraycopy(expected, 5, wantedSecond, 0, 10);
            assertArrayEquals(wantedSecond, second);
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
