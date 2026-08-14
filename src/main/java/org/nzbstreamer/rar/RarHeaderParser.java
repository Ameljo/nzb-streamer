package org.nzbstreamer.rar;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads all the block headers of a RAR volume. The parser reads only the bytes of the headers.
 *
 * <p>A RAR archive is a sequence of blocks. Each header gives its own length and the length of the
 * data that comes after it. Thus the parser can go to the next header without a read of the data.
 * The parser does these three steps for each block: read the header, keep the position of the
 * data, then skip the data. The parser is thus applicable to a stream that gets its bytes at a
 * high cost.</p>
 *
 * <p>The parser moves only forward. Thus an {@link InputStream} is sufficient. The parser does not
 * do a seek operation. The parser does not use Tika. Thus you can use it as an independent
 * component.</p>
 *
 * <p>This class holds the sequence of the blocks. {@link Rar5BlockReader} and
 * {@link Rar4BlockReader} hold the layout of a block.</p>
 */
public final class RarHeaderParser {

    private static final byte[] SIGNATURE_PREFIX = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07};
    private static final int SIGNATURE_RAR4_MARKER = 0x00;
    private static final int SIGNATURE_RAR5_MARKER = 0x01;

    /** Prevents an infinite loop with a damaged archive. No usual archive has so many blocks. */
    private static final int MAX_BLOCKS = 100_000;

    /**
     * Reads all the headers of the archive. This function reads the stream. It does not close the
     * stream. The caller keeps the control of the stream. The Tika parser interface also makes
     * this necessary.
     *
     * @throws RarParseException if the data is not a RAR archive, or if a header has bad values
     */
    public RarArchive parse(InputStream stream) throws IOException {
        RarHeaderReader reader = new RarHeaderReader(stream);
        RarBlockReader blockReader = readSignature(reader) == RarFormat.RAR5
                ? new Rar5BlockReader()
                : new Rar4BlockReader();

        List<RarBlock> blocks = new ArrayList<>();
        boolean endOfArchive = false;
        boolean truncated = false;

        while (blocks.size() < MAX_BLOCKS) {
            long blockStart = reader.position();
            try {
                RarBlock block = blockReader.readBlock(reader);
                blocks.add(block);
                if (block.type() == RarBlockType.END) {
                    endOfArchive = true;
                    break;
                }

                long nextBlock = block.dataOffset() + block.dataSize();
                if (nextBlock <= blockStart) {
                    throw new RarParseException("Block at " + blockStart + " does not advance");
                }
                reader.skipTo(nextBlock);
            } catch (EOFException e) {
                // A volume can correctly stop between two blocks. A volume can also stop in a
                // header. The parser keeps the headers that it found before this position.
                truncated = reader.position() != blockStart;
                break;
            }
        }

        RarVolumeInfo volume = blockReader.volumeInfo();
        return new RarArchive(blockReader.format(), blocks, volume.volume(), volume.volumeNumber(),
                volume.firstVolume(), volume.solid(), endOfArchive, truncated, reader.bytesRead(),
                reader.bytesSkipped());
    }

    /**
     * Reads the signature in two steps. RAR4 and RAR5 have the same first six bytes. The seventh
     * byte is different. A read of eight bytes would also remove the first byte of the first RAR4
     * block. The reader moves only forward. Thus it cannot put that byte back.
     */
    private RarFormat readSignature(RarHeaderReader reader) throws IOException {
        byte[] prefix;
        int marker;
        try {
            prefix = reader.readBytes(SIGNATURE_PREFIX.length);
            marker = reader.readU8();
        } catch (EOFException e) {
            throw new RarParseException("Not a RAR archive: stream ends inside the signature", e);
        }

        for (int i = 0; i < SIGNATURE_PREFIX.length; i++) {
            if (prefix[i] != SIGNATURE_PREFIX[i]) {
                throw new RarParseException("Not a RAR archive: unexpected signature "
                        + describeSignature(prefix, marker));
            }
        }
        if (marker == SIGNATURE_RAR4_MARKER) {
            return RarFormat.RAR4;
        }
        if (marker == SIGNATURE_RAR5_MARKER) {
            int terminator = reader.readU8();
            if (terminator != 0x00) {
                throw new RarParseException("Not a RAR archive: RAR5 signature ends with 0x"
                        + Integer.toHexString(terminator) + " instead of 0x00");
            }
            return RarFormat.RAR5;
        }
        throw new RarParseException("Not a RAR archive: unknown signature "
                + describeSignature(prefix, marker));
    }

    private String describeSignature(byte[] prefix, int marker) {
        StringBuilder hex = new StringBuilder();
        for (byte b : prefix) {
            hex.append(String.format("%02X ", b));
        }
        return hex.append(String.format("%02X", marker)).toString();
    }
}
