package org.nzbstreamer.rar;

import java.util.List;

/**
 * The blocks that the parser found in one RAR volume.
 *
 * <p>{@link #bytesRead()} and {@link #bytesSkipped()} show how the parser moved through the
 * volume. The parser reads only some hundreds of bytes. The parser skips all the data of the
 * files. These two values are correct for a volume of any size.</p>
 */
public record RarArchive(
        RarFormat format,
        List<RarBlock> blocks,
        boolean volume,
        int volumeNumber,
        boolean firstVolume,
        boolean solid,
        boolean endOfArchive,
        boolean truncated,
        long bytesRead,
        long bytesSkipped) {

    public RarArchive {
        blocks = List.copyOf(blocks);
    }

    /** The files and the directories. This list does not contain the service blocks. */
    public List<RarFileEntry> entries() {
        return blocks.stream()
                .filter(RarFileEntry.class::isInstance)
                .map(RarFileEntry.class::cast)
                .filter(entry -> entry.type() == RarBlockType.FILE)
                .toList();
    }

    /** The entries that a caller can read directly. See {@link RarFileEntry#streamable()}. */
    public List<RarFileEntry> streamableEntries() {
        return entries().stream().filter(RarFileEntry::streamable).toList();
    }

    /** The service blocks. These blocks hold the comment, the quick-open index or the recovery record. */
    public List<RarFileEntry> serviceEntries() {
        return blocks.stream()
                .filter(RarFileEntry.class::isInstance)
                .map(RarFileEntry.class::cast)
                .filter(entry -> entry.type() == RarBlockType.SERVICE)
                .toList();
    }
}
