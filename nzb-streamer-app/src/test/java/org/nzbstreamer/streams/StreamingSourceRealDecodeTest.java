package org.nzbstreamer.streams;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.nzbstreamer.decoder.MultiPartDecoder;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.VirtualFileTestData;
import org.nzbstreamer.service.SegmentFetcher;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.nzbstreamer.model.VirtualFileTestData.CHUNK_LENGTH;
import static org.nzbstreamer.model.VirtualFileTestData.expectedBytes;
import static org.nzbstreamer.model.VirtualFileTestData.fileOfTwoVolumes;

/**
 * Tests {@link StreamingSource} end to end with the real {@link MultiPartDecoder}, instead of a
 * fake that bypasses yEnc encoding entirely: builds a real yEnc article for each fake segment and
 * decodes it through the real skip/trim streaming path, the same way {@link SegmentFetcher} does
 * for a real download.
 */
class StreamingSourceRealDecodeTest {

    private static String encodeYenc(byte[] data) {
        StringBuilder line = new StringBuilder();
        for (byte value : data) {
            int plain = ((value & 0xFF) + 42) & 0xFF;
            if (plain == 0 || plain == 10 || plain == 13 || plain == 61) {
                line.append('=').append((char) ((plain + 64) & 0xFF));
            } else {
                line.append((char) plain);
            }
        }
        return line.toString();
    }

    private static String article(byte[] data) {
        return "=ybegin part=1 line=128 size=" + data.length + " name=test.bin\r\n"
                + "=ypart begin=1 end=" + data.length + "\r\n"
                + encodeYenc(data) + "\r\n"
                + "=yend size=" + data.length + " part=1 pcrc32=00000000\r\n";
    }

    /** Builds a real yEnc article from the fake segment bytes and decodes it for real. */
    private static final class RealDecodeSegments implements SegmentFetcher {

        @Override
        public void fetch(String messageId, String group, BlockingQueue<byte[]> buffer,
                          int bufferSize, int skip, int trim) throws IOException, InterruptedException {
            byte[] raw = VirtualFileTestData.segmentBytes(messageId);
            new MultiPartDecoder().decode(new StringReader(article(raw)), buffer, bufferSize, skip,
                    trim);
        }
    }

    @Test
    @Timeout(5)
    @DisplayName("a read of the whole file gives the bytes of the file, through real yEnc decoding")
    void readsTheWholeFileThroughRealDecoding() throws IOException {
        VirtualFile file = fileOfTwoVolumes();
        byte[] actual;
        try (VirtualFileStream stream =
                     new VirtualFileStream(file, new StreamingSource(file, new RealDecodeSegments()))) {
            actual = stream.readAllBytes();
        }

        assertArrayEquals(expectedBytes(), actual,
                "the stream must give the bytes of the file, without the bytes of the archive");
    }

    @Test
    @Timeout(5)
    @DisplayName("a forward and a backward seek give the bytes of the new position, through real"
            + " yEnc decoding")
    void readsAfterSeekThroughRealDecoding() throws IOException {
        byte[] expected = expectedBytes();

        VirtualFile file = fileOfTwoVolumes();
        try (VirtualFileStream stream =
                     new VirtualFileStream(file, new StreamingSource(file, new RealDecodeSegments()))) {
            byte[] first = stream.readNBytes(10);
            assertArrayEquals(Arrays.copyOfRange(expected, 0, 10), first, "the first read");

            stream.seek(30);
            byte[] afterForwardSeek = stream.readNBytes(10);
            assertArrayEquals(Arrays.copyOfRange(expected, 30, 40), afterForwardSeek,
                    "the read after a forward seek, past the volume boundary");

            stream.seek(5);
            byte[] afterBackwardSeek = stream.readNBytes(10);
            assertArrayEquals(Arrays.copyOfRange(expected, 5, 15), afterBackwardSeek,
                    "the read after a backward seek");
        }
    }

    @Test
    @Timeout(5)
    @DisplayName("the stream crosses the volume boundary without an action of the caller, through"
            + " real yEnc decoding")
    void crossesTheVolumeBoundaryThroughRealDecoding() throws IOException {
        byte[] expected = expectedBytes();

        VirtualFile file = fileOfTwoVolumes();
        try (VirtualFileStream stream =
                     new VirtualFileStream(file, new StreamingSource(file, new RealDecodeSegments()))) {
            stream.seek(CHUNK_LENGTH - 2);

            assertEquals(expected[(int) CHUNK_LENGTH - 2] & 0xFF, stream.read());
            assertEquals(expected[(int) CHUNK_LENGTH - 1] & 0xFF, stream.read());
            assertEquals(expected[(int) CHUNK_LENGTH] & 0xFF, stream.read(),
                    "this byte is the first byte of volume 2");
        }
    }

    @Test
    @Timeout(5)
    @DisplayName("a seek while the worker is still downloading ahead gives the bytes of the new"
            + " position, through real yEnc decoding")
    void seekWhileDownloadingAheadThroughRealDecoding() throws IOException, InterruptedException {
        byte[] expected = expectedBytes();

        VirtualFile file = fileOfTwoVolumes();
        try (VirtualFileStream stream =
                     new VirtualFileStream(file, new StreamingSource(file, new RealDecodeSegments()))) {
            // Read one byte only, so the worker is still ahead of the reader, with more chunks
            // already queued behind it, when the seek discards them.
            assertEquals(expected[0] & 0xFF, stream.read());

            stream.seek(40);
            byte[] afterSeek = stream.readNBytes(10);
            assertArrayEquals(Arrays.copyOfRange(expected, 40, 50), afterSeek,
                    "the read after a seek must not give bytes of the position before it");
        }
    }
}
