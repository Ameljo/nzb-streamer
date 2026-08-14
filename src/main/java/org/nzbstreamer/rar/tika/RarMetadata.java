package org.nzbstreamer.rar.tika;

/**
 * The metadata keys that {@link RarHeaderTikaParser} writes.
 *
 * <p>Each entry has an index in its keys. Examples are {@code rar:entry:0:name} and
 * {@code rar:entry:0:dataOffset}. All the values are character strings. A caller that needs the
 * values as numbers must use {@link RarArchiveCollector}.</p>
 */
public final class RarMetadata {

    public static final String PREFIX = "rar:";

    public static final String FORMAT = PREFIX + "format";
    public static final String VOLUME = PREFIX + "volume";
    public static final String VOLUME_NUMBER = PREFIX + "volumeNumber";
    public static final String FIRST_VOLUME = PREFIX + "firstVolume";
    public static final String SOLID = PREFIX + "solid";
    public static final String END_OF_ARCHIVE = PREFIX + "endOfArchive";
    public static final String TRUNCATED = PREFIX + "truncated";
    public static final String ENTRY_COUNT = PREFIX + "entryCount";
    public static final String BYTES_READ = PREFIX + "bytesRead";
    public static final String BYTES_SKIPPED = PREFIX + "bytesSkipped";

    public static final String ENTRY_NAME = "name";
    public static final String ENTRY_DATA_OFFSET = "dataOffset";
    public static final String ENTRY_PACKED_SIZE = "packedSize";
    public static final String ENTRY_UNPACKED_SIZE = "unpackedSize";
    public static final String ENTRY_METHOD = "method";
    public static final String ENTRY_STORED = "stored";
    public static final String ENTRY_DIRECTORY = "directory";
    public static final String ENTRY_ENCRYPTED = "encrypted";
    public static final String ENTRY_SPLIT_BEFORE = "splitBefore";
    public static final String ENTRY_SPLIT_AFTER = "splitAfter";
    public static final String ENTRY_CRC32 = "crc32";

    private RarMetadata() {
    }

    /** Makes the key for one field of one entry. An example is {@code rar:entry:2:dataOffset}. */
    public static String entryKey(int index, String field) {
        return PREFIX + "entry:" + index + ":" + field;
    }
}
