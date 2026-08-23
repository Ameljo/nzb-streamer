package org.nzbstreamer.rar;

/**
 * The type of a block.
 *
 * <p>These types are the same for RAR4 and for RAR5. Thus a caller does not examine the format of
 * the archive.</p>
 */
public enum RarBlockType {

    /** The main header. It gives the volume flags, the solid flag and the volume number. */
    MAIN,

    /** A file or a directory in the archive. */
    FILE,

    /**
     * A service block. RAR5 uses a service block for the comment, for the quick-open index and for
     * the recovery record. RAR4 uses a newsub block for the same data.
     */
    SERVICE,

    /** A RAR4 comment block, an authenticity block or an old sub block. */
    SUB,

    /**
     * The encryption header. All the blocks after this block are encrypted. The parser cannot read
     * them.
     */
    ENCRYPTION,

    /** The end of the archive. */
    END,

    /** A block that this parser does not know. The parser uses the sizes in the header to skip it. */
    UNKNOWN
}
