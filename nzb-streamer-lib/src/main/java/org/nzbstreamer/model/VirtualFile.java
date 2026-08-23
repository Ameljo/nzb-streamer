package org.nzbstreamer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A file that a player can read. Its bytes are in one post or in many posts.
 *
 * <p>The file gives a continuous sequence of bytes, from 0 to {@code size - 1}. The function
 * {@link #locate(long)} changes a position of the file into a position in a segment. Thus a caller
 * does not know the chunks, the volumes or the archive.</p>
 *
 * <p>This class has no identity of its own -- it is a plain value built from a list of chunks.
 * An application that persists virtual files owns its own identity (an id, a thumbnail
 * reference, ...) on its own entity type and maps to/from this class.</p>
 */
public class VirtualFile {

    private String filename;

    private String contentType;

    private Long size;

    private List<VirtualFileChunk> chunks = new ArrayList<>();

    public VirtualFile() {
    }

    /**
     * Makes a file of all the bytes of one post.
     *
     * @param size     the number of bytes of the post
     * @param filename the name of the file
     * @param nzbFile  the post
     */
    public VirtualFile(long size, String filename, NzbFile nzbFile) {
        this.filename = filename;
        this.size = size;
        this.chunks.add(new VirtualFileChunk(nzbFile, 0, 0, size, 0,
                nzbFile.getSegments().size() - 1));
    }

    /** Makes a file of the given parts of one post or of many posts. */
    public VirtualFile(String filename, String contentType, List<VirtualFileChunk> chunks) {
        this.filename = filename;
        this.contentType = contentType;
        this.chunks = new ArrayList<>(chunks);
        this.size = chunks.stream().mapToLong(VirtualFileChunk::getLength).sum();
    }

    /**
     * The segment that holds the byte at the given position of the file.
     *
     * @param segment            the segment to download
     * @param group              the newsgroup of that segment
     * @param byteInSegment      the position of the byte in that segment
     * @param bytesLeftInSegment the number of bytes of the file after that position. The value
     *                           stops at the end of the segment and at the end of the chunk.
     */
    public record Location(Segment segment, String group, int byteInSegment,
                           int bytesLeftInSegment) {
    }

    /** Returns true when a byte is at this position. */
    public boolean hasNext(long filePosition) {
        return size != null && filePosition >= 0 && filePosition < size;
    }

    /**
     * Finds the segment for a position of the file.
     *
     * @throws IllegalArgumentException if no byte is at this position
     */
    public Location locate(long filePosition) {
        if (!hasNext(filePosition)) {
            throw new IllegalArgumentException("position " + filePosition + " is not in " + filename
                    + " (size " + size + ")");
        }

        for (VirtualFileChunk chunk : chunks) {
            if (!chunk.contains(filePosition)) {
                continue;
            }

            // The offset of the chunk applies to its first segment. Thus the position in the
            // segments of the chunk is the offset plus the position in the chunk.
            long inSegments = chunk.getOffset() + (filePosition - chunk.getFileStart());
            long bytesLeftInChunk = chunk.fileEnd() - filePosition;

            for (Segment segment : chunk.segments()) {
                if (inSegments < segment.getSize()) {
                    long leftInSegment = segment.getSize() - inSegments;
                    int usable = (int) Math.min(leftInSegment, bytesLeftInChunk);
                    return new Location(segment, chunk.group(), (int) inSegments, usable);
                }
                inSegments -= segment.getSize();
            }
            throw new IllegalStateException("the segments of the chunk do not hold position "
                    + filePosition + " of " + filename);
        }
        throw new IllegalStateException("no chunk holds position " + filePosition + " of " + filename);
    }

    /** The number of segments of all the chunks. */
    public int segmentCount() {
        return chunks.stream().mapToInt(chunk -> chunk.segments().size()).sum();
    }

    public long getSize() {
        return size == null ? 0 : size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String filename() {
        return filename;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public List<VirtualFileChunk> getChunks() {
        return chunks;
    }

    public void setChunks(List<VirtualFileChunk> chunks) {
        this.chunks = chunks;
    }
}
