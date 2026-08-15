package org.nzbstreamer.rar;

/**
 * The properties of a file in an archive.
 *
 * @param directory   true when the entry is a directory and not a file
 * @param encrypted   true when a password protects the data
 * @param splitBefore true when the data continues from the volume before this volume
 * @param splitAfter  true when the data continues in the volume after this volume
 */
public record RarFileFlags(
        boolean directory,
        boolean encrypted,
        boolean splitBefore,
        boolean splitAfter) {

    public static final RarFileFlags NONE = new RarFileFlags(false, false, false, false);
}
