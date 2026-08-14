package org.nzbstreamer.streams;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.Segment;
import org.nzbstreamer.model.Segments;
import org.nzbstreamer.model.VirtualFile;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the movement of the cursor in {@link VirtualFileInputStream}.
 *
 * <p>These tests use segments that the test makes. They do not use a news server. The test counts
 * the workers, because one new worker downloads one segment again. A move inside the segment that
 * is in memory must not make a new worker.</p>
 */
class VirtualFileInputStreamTest {

    private static final int SEGMENT_SIZE = 100;
    private static final int SEGMENT_COUNT = 4;
    private static final int FILE_SIZE = SEGMENT_SIZE * SEGMENT_COUNT;

    /** The byte of the file at the given position. Each position has a different value. */
    private static byte contentAt(long position) {
        return (byte) (position % 251);
    }

    private static byte[] segmentData(int segment) {
        byte[] data = new byte[SEGMENT_SIZE];
        for (int i = 0; i < SEGMENT_SIZE; i++) {
            data[i] = contentAt((long) segment * SEGMENT_SIZE + i);
        }
        return data;
    }

    private static NzbFile nzbFile() {
        NzbFile nzbFile = new NzbFile();
        nzbFile.setSubject("\"test.rar\" yEnc (1/1)");
        nzbFile.setSize(FILE_SIZE);

        List<Segment> list = new ArrayList<>();
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            Segment segment = new Segment();
            segment.setValue("segment-" + i);
            segment.setNumber(BigInteger.valueOf(i + 1L));
            segment.setBytes(BigInteger.valueOf(SEGMENT_SIZE));
            segment.setSize(SEGMENT_SIZE);
            segment.setStartPosition((long) i * SEGMENT_SIZE);
            list.add(segment);
        }
        Segments segments = new Segments();
        segments.setSegment(list);
        nzbFile.setSegments(segments);
        return nzbFile;
    }

    /** Counts the workers and gives the segments of the test to the stream. */
    private static final class TestWorkers implements VirtualFileInputStream.DownloadWorkerFactory {

        private final AtomicInteger starts = new AtomicInteger();

        @Override
        public Callable<Boolean> create(AtomicInteger segmentIndex, VirtualFile file,
                                        java.util.concurrent.BlockingQueue<byte[]> bufferQueue,
                                        java.util.concurrent.atomic.AtomicBoolean endOfSegments,
                                        java.util.concurrent.atomic.AtomicBoolean running) {
            starts.incrementAndGet();
            return () -> {
                while (running.get() && segmentIndex.get() < SEGMENT_COUNT) {
                    bufferQueue.put(segmentData(segmentIndex.get()));
                    segmentIndex.incrementAndGet();
                }
                // The production worker also does this on each exit.
                endOfSegments.set(true);
                running.set(false);
                return true;
            };
        }
    }

    private static VirtualFile wholeFile(NzbFile nzbFile) {
        return new VirtualFile(FILE_SIZE, "test.rar", nzbFile);
    }

    /** A file in the archive. It starts at the given offset and it is not at a segment boundary. */
    private static VirtualFile innerFile(NzbFile nzbFile, long offset, long size) {
        VirtualFile file = new VirtualFile(size, "movie.mkv", nzbFile);
        file.setOffset(offset);
        return file;
    }

    @Test
    @DisplayName("a sequential read gives all the bytes of the file")
    void readsAllBytesInOrder() throws IOException {
        TestWorkers workers = new TestWorkers();
        try (VirtualFileInputStream stream = new VirtualFileInputStream(wholeFile(nzbFile()), workers)) {
            for (int i = 0; i < FILE_SIZE; i++) {
                assertEquals(contentAt(i) & 0xFF, stream.read(), "byte at position " + i);
            }
            assertEquals(-1, stream.read(), "the stream must stop at the end of the file");
        }
        assertEquals(1, workers.starts.get(), "a sequential read needs only one worker");
    }

    @Test
    @DisplayName("a skip inside the segment in memory does not download the segment again")
    void skipInsideSegmentKeepsTheChunk() throws IOException {
        TestWorkers workers = new TestWorkers();
        try (VirtualFileInputStream stream = new VirtualFileInputStream(wholeFile(nzbFile()), workers)) {
            assertEquals(contentAt(0) & 0xFF, stream.read());
            int workersAfterFirstRead = workers.starts.get();

            assertEquals(9, stream.skip(9));

            assertEquals(contentAt(10) & 0xFF, stream.read(), "the cursor must be at position 10");
            assertEquals(workersAfterFirstRead, workers.starts.get(),
                    "the segment is in memory, thus the stream must not start a new worker");
        }
    }

    @Test
    @DisplayName("a skip to a different segment starts a new worker and lands on the correct byte")
    void skipToDifferentSegment() throws IOException {
        TestWorkers workers = new TestWorkers();
        try (VirtualFileInputStream stream = new VirtualFileInputStream(wholeFile(nzbFile()), workers)) {
            assertEquals(contentAt(0) & 0xFF, stream.read());
            int workersAfterFirstRead = workers.starts.get();

            assertEquals(249, stream.skip(249));

            assertEquals(contentAt(250) & 0xFF, stream.read(), "the cursor must be at position 250");
            assertNotEquals(workersAfterFirstRead, workers.starts.get(),
                    "segment 2 is not in memory, thus the stream must start a new worker");
        }
    }

    @Test
    @DisplayName("a read after a skip does not give the end of the file")
    void readAfterSkipDoesNotGiveEndOfFile() throws IOException {
        TestWorkers workers = new TestWorkers();
        try (VirtualFileInputStream stream = new VirtualFileInputStream(wholeFile(nzbFile()), workers)) {
            stream.skip(150);

            int value = stream.read();

            assertNotEquals(-1, value, "the stream must give data after a skip");
            assertEquals(contentAt(150) & 0xFF, value);
        }
    }

    @Test
    @DisplayName("a rearward seek inside the segment in memory does not download the segment again")
    void seekBackwardsInsideSegment() throws Exception {
        TestWorkers workers = new TestWorkers();
        try (VirtualFileInputStream stream = new VirtualFileInputStream(wholeFile(nzbFile()), workers)) {
            for (int i = 0; i < 50; i++) {
                stream.read();
            }
            int workersAfterReads = workers.starts.get();

            assertEquals(10, stream.seek(10, 0));

            assertEquals(contentAt(10) & 0xFF, stream.read());
            assertEquals(workersAfterReads, workers.starts.get(),
                    "the segment is in memory, thus a rearward move must not start a new worker");
        }
    }

    @Test
    @DisplayName("a file in the archive starts at its offset")
    void innerFileStartsAtItsOffset() throws IOException {
        TestWorkers workers = new TestWorkers();
        VirtualFile inner = innerFile(nzbFile(), 150, 100);

        try (VirtualFileInputStream stream = new VirtualFileInputStream(inner, workers)) {
            for (int i = 0; i < 20; i++) {
                assertEquals(contentAt(150 + i) & 0xFF, stream.read(),
                        "byte " + i + " of the file in the archive");
            }
        }
    }

    @Test
    @DisplayName("a skip in a file in the archive lands on the correct byte")
    void skipInsideInnerFile() throws IOException {
        TestWorkers workers = new TestWorkers();
        VirtualFile inner = innerFile(nzbFile(), 150, 100);

        try (VirtualFileInputStream stream = new VirtualFileInputStream(inner, workers)) {
            assertEquals(contentAt(150) & 0xFF, stream.read());

            assertEquals(20, stream.skip(20));

            assertEquals(contentAt(171) & 0xFF, stream.read(),
                    "position 21 of the file in the archive is position 171 of the NZB file");
        }
    }

    @Test
    @DisplayName("a skip across a segment boundary in a file in the archive lands on the correct byte")
    void skipAcrossSegmentInsideInnerFile() throws IOException {
        TestWorkers workers = new TestWorkers();
        VirtualFile inner = innerFile(nzbFile(), 150, 200);

        try (VirtualFileInputStream stream = new VirtualFileInputStream(inner, workers)) {
            assertEquals(contentAt(150) & 0xFF, stream.read());

            assertEquals(120, stream.skip(120));

            assertEquals(contentAt(271) & 0xFF, stream.read(),
                    "position 121 of the file in the archive is position 271 of the NZB file");
            assertTrue(workers.starts.get() >= 2, "the stream must download the next segment");
        }
    }
}
