package org.nzbstreamer.rar;

/**
 * A file or a directory in a RAR archive. The data comes from the header of the entry.
 *
 * <p>When {@link #stored()} is true, the data in the archive is equal to the data of the initial
 * file. Thus a caller can read the file directly from the archive. The caller uses
 * {@link #dataOffset()} and {@link #packedSize()}. The caller does not decompress the data.</p>
 *
 * <p>{@link #splitBefore()} and {@link #splitAfter()} show that the data of the file continues in
 * a different volume. This entry then gives only the part of the file that is in this volume.</p>
 *
 * @param block        the position and the size of this entry in the volume
 * @param name         the name of the file, with the directories before it
 * @param unpackedSize the length of the complete file, also when this volume holds one part of it
 * @param crc32        the CRC32 of the complete file, or null when the header does not give it
 * @param mtime        the time of the last change, in seconds, or null when the header omits it
 */
public record RarFileEntry(
        RarBlockInfo block,
        String name,
        long unpackedSize,
        RarCompression compression,
        RarFileFlags fileFlags,
        Long crc32,
        Long mtime,
        int hostOs) implements RarBlock {

    /** The length of the data in this volume. The value is equal to {@link #dataSize()}. */
    public long packedSize() {
        return dataSize();
    }

    /** The compression level. The value 0 shows a file without compression. */
    public int method() {
        return compression.method();
    }

    /** Returns true when the archive keeps the file without compression. */
    public boolean stored() {
        return compression.stored();
    }

    public boolean solid() {
        return compression.solid();
    }

    public boolean directory() {
        return fileFlags.directory();
    }

    public boolean encrypted() {
        return fileFlags.encrypted();
    }

    public boolean splitBefore() {
        return fileFlags.splitBefore();
    }

    public boolean splitAfter() {
        return fileFlags.splitAfter();
    }

    /**
     * Returns true when a caller can read the data directly. The entry must be stored. The entry
     * must not be a directory. The entry must not be encrypted.
     */
    public boolean streamable() {
        return stored() && !directory() && !encrypted();
    }
}
