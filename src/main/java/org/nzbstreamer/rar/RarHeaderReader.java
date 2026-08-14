package org.nzbstreamer.rar;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * A cursor that moves forward in an {@link InputStream}. It reads the fields of a RAR header.
 *
 * <p>{@link #skipTo(long)} is the most important function of this class. It uses
 * {@link InputStream#skip(long)} to move across the data of a file. It reads the data only when
 * the stream does not skip. {@link #bytesRead()} and {@link #bytesSkipped()} give the two counts.
 * Thus a test can show that the parser does not read all the archive.</p>
 *
 * <p>RAR keeps an integer of more than one byte in the little-endian sequence. This class returns
 * each value as a {@code long}. Thus an unsigned 32-bit field keeps its value.</p>
 */
public final class RarHeaderReader {

    private static final int DISCARD_BUFFER_SIZE = 8192;
    private static final int MAX_VINT_BYTES = 10;

    private final InputStream stream;
    private long position;
    private long bytesRead;
    private long bytesSkipped;
    private byte[] discardBuffer;

    public RarHeaderReader(InputStream stream) {
        this.stream = stream;
    }

    /** The offset of the cursor. The count starts at the first byte that this reader gets. */
    public long position() {
        return position;
    }

    /** The number of bytes that this reader read from the stream. */
    public long bytesRead() {
        return bytesRead;
    }

    /** The number of bytes that this reader skipped. */
    public long bytesSkipped() {
        return bytesSkipped;
    }

    public int readU8() throws IOException {
        int value = stream.read();
        if (value < 0) {
            throw new EOFException("End of stream at offset " + position);
        }
        position++;
        bytesRead++;
        return value;
    }

    public int readU16() throws IOException {
        byte[] buffer = readBytes(2);
        return (buffer[0] & 0xFF) | ((buffer[1] & 0xFF) << 8);
    }

    public long readU32() throws IOException {
        byte[] buffer = readBytes(4);
        return (buffer[0] & 0xFFL)
                | ((buffer[1] & 0xFFL) << 8)
                | ((buffer[2] & 0xFFL) << 16)
                | ((buffer[3] & 0xFFL) << 24);
    }

    public long readU64() throws IOException {
        byte[] buffer = readBytes(8);
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (buffer[i] & 0xFFL);
        }
        return value;
    }

    /**
     * Reads a RAR5 integer of a variable length. Each byte holds seven data bits. The first byte
     * holds the least significant bits. Bit 8 shows that one more byte follows.
     */
    public long readVint() throws IOException {
        long value = 0;
        for (int i = 0; i < MAX_VINT_BYTES; i++) {
            int b = readU8();
            value |= (long) (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new RarParseException("Malformed vint (no terminator within " + MAX_VINT_BYTES
                + " bytes) ending at offset " + position);
    }

    public byte[] readBytes(int length) throws IOException {
        byte[] buffer = new byte[length];
        readFully(buffer, 0, length);
        return buffer;
    }

    public void readFully(byte[] destination, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int read = stream.read(destination, offset + done, length - done);
            if (read < 0) {
                throw new EOFException("End of stream after " + (position + done) + " bytes, needed "
                        + (length - done) + " more");
            }
            done += read;
        }
        position += length;
        bytesRead += length;
    }

    /**
     * Moves the cursor forward to {@code target}. The reader skips the bytes when the stream
     * permits it. If the stream does not skip, the reader reads the bytes.
     *
     * <p>{@link InputStream#skip(long)} can return 0 also when the stream has more data. Thus this
     * function calls {@code skip} one more time before it reads the bytes into a buffer. The
     * parser stays correct with a stream that does not skip. But it then reads more bytes.</p>
     */
    public void skipTo(long target) throws IOException {
        if (target == position) {
            return;
        }
        if (target < position) {
            throw new RarParseException("Refusing to seek backwards from " + position + " to "
                    + target + "; the parser only moves forward");
        }

        long remaining = target - position;
        int consecutiveZeroSkips = 0;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                position += skipped;
                bytesSkipped += skipped;
                consecutiveZeroSkips = 0;
                continue;
            }

            if (++consecutiveZeroSkips < 2) {
                continue;
            }
            if (discardBuffer == null) {
                discardBuffer = new byte[DISCARD_BUFFER_SIZE];
            }
            int wanted = (int) Math.min(discardBuffer.length, remaining);
            int read = stream.read(discardBuffer, 0, wanted);
            if (read < 0) {
                throw new EOFException("End of stream at offset " + position + " while skipping to "
                        + target);
            }
            remaining -= read;
            position += read;
            bytesRead += read;
            consecutiveZeroSkips = 0;
        }
    }
}
