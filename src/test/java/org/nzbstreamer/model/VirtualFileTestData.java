package org.nzbstreamer.model;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes the posts and the files of the tests.
 *
 * <p>The archive has two volumes. Each volume is one post with three segments of 10 bytes. The
 * file starts at byte 2 of a volume and has 25 bytes in it. Thus the file uses a part of the first
 * segment, all the second segment and a part of the third segment, in each volume.</p>
 */
public final class VirtualFileTestData {

    public static final int SEGMENT_SIZE = 10;
    public static final int SEGMENTS_PER_VOLUME = 3;
    public static final long OFFSET = 2;
    public static final long CHUNK_LENGTH = 25;

    private VirtualFileTestData() {
    }

    /** The byte of a segment. Each segment of each volume has different values. */
    public static byte byteOf(int volume, int segment, int index) {
        return (byte) (volume * 100 + segment * 10 + index);
    }

    /** The address of a segment, for example {@code v1-s2}. */
    public static String messageId(int volume, int segment) {
        return "v" + volume + "-s" + segment;
    }

    /** The bytes of one segment. */
    public static byte[] segmentBytes(String messageId) {
        String[] parts = messageId.split("-");
        int volume = Integer.parseInt(parts[0].substring(1));
        int index = Integer.parseInt(parts[1].substring(1));
        byte[] bytes = new byte[SEGMENT_SIZE];
        for (int i = 0; i < SEGMENT_SIZE; i++) {
            bytes[i] = byteOf(volume, index, i);
        }
        return bytes;
    }

    public static NzbFile volume(int number) {
        NzbFile nzbFile = new NzbFile();
        nzbFile.setSubject("\"volume" + number + ".rar\" yEnc (1/1)");

        nzbFile.setGroups(new ArrayList<>(List.of("alt.binaries.test")));

        List<Segment> list = new ArrayList<>();
        for (int i = 0; i < SEGMENTS_PER_VOLUME; i++) {
            Segment segment = new Segment();
            segment.setValue(messageId(number, i));
            segment.setNumber(BigInteger.valueOf(i + 1L));
            segment.setBytes(BigInteger.valueOf(SEGMENT_SIZE));
            segment.setSize(SEGMENT_SIZE);
            segment.setStartPosition((long) i * SEGMENT_SIZE);
            list.add(segment);
        }
        nzbFile.setSegments(list);
        nzbFile.setSize((long) SEGMENTS_PER_VOLUME * SEGMENT_SIZE);
        return nzbFile;
    }

    /** A file of 50 bytes with one chunk in each of the two volumes. */
    public static VirtualFile fileOfTwoVolumes() {
        return new VirtualFile("movie.mkv", "video/x-matroska", List.of(
                new VirtualFileChunk(volume(1), 0, OFFSET, CHUNK_LENGTH, 0, SEGMENTS_PER_VOLUME - 1),
                new VirtualFileChunk(volume(2), CHUNK_LENGTH, OFFSET, CHUNK_LENGTH, 0,
                        SEGMENTS_PER_VOLUME - 1)));
    }

    /** The bytes that the file must give, in sequence. */
    public static byte[] expectedBytes() {
        java.io.ByteArrayOutputStream expected = new java.io.ByteArrayOutputStream();
        for (int volume = 1; volume <= 2; volume++) {
            long position = OFFSET;
            for (long written = 0; written < CHUNK_LENGTH; written++, position++) {
                expected.write(byteOf(volume, (int) (position / SEGMENT_SIZE),
                        (int) (position % SEGMENT_SIZE)));
            }
        }
        return expected.toByteArray();
    }
}
