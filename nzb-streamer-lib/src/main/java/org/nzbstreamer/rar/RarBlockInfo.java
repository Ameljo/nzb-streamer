package org.nzbstreamer.rar;

/**
 * The position and the size of one block. Each block of an archive has this data.
 *
 * <p>With {@code dataOffset} and {@code dataSize} the parser can go to the next block. It does not
 * read the data.</p>
 */
public record RarBlockInfo(
        RarBlockType type,
        long headerOffset,
        long headerSize,
        long dataOffset,
        long dataSize,
        long flags) {
}
