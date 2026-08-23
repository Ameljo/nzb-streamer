package org.nzbstreamer.model;

import java.util.List;

/**
 * A continuous part of a {@link VirtualFile}. One chunk is in one post.
 *
 * <p>A file that is in one post has one chunk. A file in an archive of many volumes has one chunk
 * for each volume, because each volume is a different post.</p>
 *
 * <p>The chunk gives the segments of that post that hold its bytes. The segments are always in
 * sequence, thus the chunk keeps the first index and the last index and not a list.</p>
 */
public class VirtualFileChunk {

    private NzbFile nzbFile;

    /** The position of the first byte of this chunk in the file. */
    private long fileStart;

    /** The position of the first byte of the file in the first segment of this chunk. */
    private long offset;

    /** The number of bytes of the file in this chunk. */
    private long length;

    /** The first segment of the post that holds bytes of this chunk. */
    private int firstSegment;

    /** The last segment of the post that holds bytes of this chunk. */
    private int lastSegment;

    public VirtualFileChunk() {
    }

    public VirtualFileChunk(NzbFile nzbFile, long fileStart, long offset, long length,
                            int firstSegment, int lastSegment) {
        this.nzbFile = nzbFile;
        this.fileStart = fileStart;
        this.offset = offset;
        this.length = length;
        this.firstSegment = firstSegment;
        this.lastSegment = lastSegment;
    }

    /** The position of the file after the last byte of this chunk. */
    public long fileEnd() {
        return fileStart + length;
    }

    public boolean contains(long filePosition) {
        return filePosition >= fileStart && filePosition < fileEnd();
    }

    /** The segments of the post that hold the bytes of this chunk, in sequence. */
    public List<Segment> segments() {
        return nzbFile.getSegments().subList(firstSegment, lastSegment + 1);
    }

    /** The newsgroup of the post. A download needs it with the address of the segment. */
    public String group() {
        return nzbFile.getGroups().getFirst();
    }

    public NzbFile getNzbFile() {
        return nzbFile;
    }

    public long getFileStart() {
        return fileStart;
    }

    public long getOffset() {
        return offset;
    }

    public long getLength() {
        return length;
    }

    public int getFirstSegment() {
        return firstSegment;
    }

    public int getLastSegment() {
        return lastSegment;
    }
}
