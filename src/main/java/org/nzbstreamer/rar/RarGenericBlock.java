package org.nzbstreamer.rar;

/**
 * A block that is not a file.
 *
 * <p>The main header, the end of the archive, the encryption header and an unknown block use this
 * type. The parser keeps these blocks. Thus a caller can see all the blocks of the archive.</p>
 */
public record RarGenericBlock(RarBlockInfo block) implements RarBlock {
}
