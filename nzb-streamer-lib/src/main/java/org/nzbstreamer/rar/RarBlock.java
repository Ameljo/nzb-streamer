package org.nzbstreamer.rar;

/**
 * One block of a RAR archive.
 *
 * <p>Each block holds a {@link RarBlockInfo} with its position and its size. The functions below
 * give that data directly, because a caller usually does not need the record itself.</p>
 */
public sealed interface RarBlock permits RarGenericBlock, RarFileEntry {

    RarBlockInfo block();

    default RarBlockType type() {
        return block().type();
    }

    /** The offset of the first byte of the header of this block. */
    default long headerOffset() {
        return block().headerOffset();
    }

    /** The length of the header in bytes, from {@link #headerOffset()} to {@link #dataOffset()}. */
    default long headerSize() {
        return block().headerSize();
    }

    /** The offset of the first data byte. For a file, the content starts at this offset. */
    default long dataOffset() {
        return block().dataOffset();
    }

    /** The length of the data in bytes. The value is 0 when the block has no data. */
    default long dataSize() {
        return block().dataSize();
    }

    /** The header flags. RAR4 and RAR5 give different meanings to these flags. */
    default long flags() {
        return block().flags();
    }
}
