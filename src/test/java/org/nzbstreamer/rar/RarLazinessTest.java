package org.nzbstreamer.rar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shows that the parser does not read the data of a file.
 *
 * <p>This is the primary property of the parser. The parser must operate with a stream that gets
 * its bytes at a high cost. A read of one byte of data has the same cost as a read of the full
 * data. Thus these tests use a stream that fails when the parser reads one byte of data.</p>
 */
class RarLazinessTest {

    /** Fails when the parser reads a byte of data. The parser can skip these bytes. */
    private static final class PayloadGuardStream extends InputStream {

        private final byte[] data;
        private final List<long[]> forbiddenRanges;
        private long position;

        PayloadGuardStream(byte[] data, List<long[]> forbiddenRanges) {
            this.data = data;
            this.forbiddenRanges = forbiddenRanges;
        }

        private void checkNotPayload(long from, long length) {
            for (long[] range : forbiddenRanges) {
                long overlapStart = Math.max(from, range[0]);
                long overlapEnd = Math.min(from + length, range[0] + range[1]);
                if (overlapStart < overlapEnd) {
                    throw new AssertionError("parser read " + (overlapEnd - overlapStart)
                            + " payload byte(s) at offset " + overlapStart
                            + "; payloads must be skipped, not read");
                }
            }
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            checkNotPayload(position, 1);
            return data[(int) position++] & 0xFF;
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            int count = (int) Math.min(length, data.length - position);
            checkNotPayload(position, count);
            System.arraycopy(data, (int) position, destination, offset, count);
            position += count;
            return count;
        }

        @Override
        public long skip(long count) {
            long actual = Math.min(count, data.length - position);
            position += actual;
            return actual;
        }
    }

    /** A stream that does not skip. The parser must then read the bytes. */
    private static final class NoSkipStream extends InputStream {

        private final InputStream delegate;

        NoSkipStream(byte[] data) {
            this.delegate = new ByteArrayInputStream(data);
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            return delegate.read(destination, offset, length);
        }

        @Override
        public long skip(long count) {
            return 0;
        }
    }

    private static List<long[]> payloadRanges(RarArchive archive) {
        List<long[]> ranges = new ArrayList<>();
        for (RarBlock block : archive.blocks()) {
            if (block.dataSize() > 0) {
                ranges.add(new long[]{block.dataOffset(), block.dataSize()});
            }
        }
        return ranges;
    }

    @Test
    @DisplayName("payload bytes are skipped, never read")
    void neverReadsPayloadBytes() throws IOException {
        for (String fixture : List.of("rar5-single.rar", "rar5-multi.rar", "rar5-vol.part1.rar",
                "rar5-comment.rar")) {
            byte[] data = RarFixtures.bytes(fixture);
            List<long[]> payloads = payloadRanges(RarFixtures.parse(fixture));
            assertTrue(payloads.size() > 0, fixture + " should contain at least one payload");

            // The stream throws an error when the parser reads a byte of data.
            new RarHeaderParser().parse(new PayloadGuardStream(data, payloads));
        }
    }

    @Test
    @DisplayName("reads a few hundred bytes regardless of how large the archive is")
    void readsOnlyHeaders() throws IOException {
        byte[] data = RarFixtures.bytes("rar5-vol.part1.rar");
        RarArchive parsed = RarFixtures.parse("rar5-vol.part1.rar");

        assertTrue(parsed.bytesRead() < 512,
                "expected only header bytes, read " + parsed.bytesRead() + " of " + data.length);
        assertTrue(parsed.bytesRead() + parsed.bytesSkipped() < 512,
                "the file continues in the next volume, thus the walk stops at its header and does"
                        + " not move over the payload at all");
    }

    @Test
    @DisplayName("still correct on a stream that refuses to skip, just not lazy")
    void fallsBackToReadingWhenSkipIsUseless() throws IOException {
        byte[] data = RarFixtures.bytes("rar5-multi.rar");
        RarArchive viaSkip = RarFixtures.parse("rar5-multi.rar");
        RarArchive viaRead = new RarHeaderParser().parse(new NoSkipStream(data));

        assertEquals(viaSkip.entries(), viaRead.entries(),
                "a stream with a useless skip() must still yield identical entries");
        assertTrue(viaRead.bytesRead() > viaSkip.bytesRead(),
                "the fallback reads the payloads it cannot skip");
        assertEquals(0, viaRead.bytesSkipped());
    }
}
