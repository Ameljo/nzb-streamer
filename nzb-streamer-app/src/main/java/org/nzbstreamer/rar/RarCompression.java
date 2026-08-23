package org.nzbstreamer.rar;

/**
 * How the archive keeps the data of a file.
 *
 * @param method 0 for a file without compression (m0), 1 to 5 for the compression levels
 * @param solid  true when the file uses the data of the file before it
 */
public record RarCompression(int method, boolean solid) {

    public static final RarCompression STORED = new RarCompression(0, false);

    /** Returns true when the archive keeps the file without compression. */
    public boolean stored() {
        return method == 0;
    }
}
