package org.nzbstreamer.rar;

/**
 * The data that the main header gives about the volume.
 *
 * @param volume       true when this file is one volume of a set of volumes
 * @param volumeNumber the number of the volume, or -1 when the format does not give it
 * @param firstVolume  true when this file is the first volume of the set
 * @param solid        true when the files use the data of the files before them
 */
public record RarVolumeInfo(boolean volume, int volumeNumber, boolean firstVolume, boolean solid) {

    public static final RarVolumeInfo SINGLE_FILE = new RarVolumeInfo(false, -1, false, false);
}
