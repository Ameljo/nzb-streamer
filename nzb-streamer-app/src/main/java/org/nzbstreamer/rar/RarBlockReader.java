package org.nzbstreamer.rar;

import java.io.EOFException;
import java.io.IOException;

/**
 * Reads the blocks of one generation of the RAR format.
 *
 * <p>{@link RarHeaderParser} does the same steps for RAR4 and for RAR5: read a block, then go to
 * the position after its data. Only the layout of a block is different. Each implementation of
 * this interface holds the layout of one generation.</p>
 *
 * <p>An implementation keeps the data of the main header. Thus it must read only one archive.</p>
 */
interface RarBlockReader {

    RarFormat format();

    /**
     * Reads the next block. The cursor is at the first byte of the header. After this function the
     * cursor is in the header or at its end. The parser then goes to the next block.
     *
     * @throws EOFException     if the stream stops before the end of the header
     * @throws RarParseException if a field of the header has a value that is not possible
     */
    RarBlock readBlock(RarHeaderReader reader) throws IOException;

    /** The data from the main header. The value is correct after the parser reads that header. */
    RarVolumeInfo volumeInfo();
}
