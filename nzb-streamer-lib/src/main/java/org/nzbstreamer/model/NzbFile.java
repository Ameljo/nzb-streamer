package org.nzbstreamer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One post of an NZB.
 *
 * <p>Groups and segments live directly on this class: groups as a plain list, segments as a plain
 * list read from and written to the {@code <segments>} element (see {@link #segments}).</p>
 */
public class NzbFile {

    protected List<String> groups = new ArrayList<>();

    protected List<Segment> segments = new ArrayList<>();

    protected String poster;
    protected Long date;
    protected String subject;

    private long size;

    public NzbFile() {
    }

    public List<String> getGroups() { return groups; }
    public void setGroups(List<String> groups) { this.groups = groups; }
    public List<Segment> getSegments() { return segments; }
    public void setSegments(List<Segment> segments) { this.segments = segments; }
    public String getPoster() { return poster; }
    public void setPoster(String poster) { this.poster = poster; }
    public Long getDate() { return date; }
    public void setDate(Long date) { this.date = date; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public long getSize() {
        return size;
    }
    public void setSize(long size) {
        this.size = size;
    }

    public Long getTotalBytes() {
        Long total = 0L;
        if (segments != null) {
            for (Segment segment : segments) {
                if (segment.getBytes() != null) {
                    total += segment.getBytes().longValue();
                }
            }
        }
        return total;
    }

    public int getSegmentAtPosition(long position) {
        long accumulated = 0L;
        if (segments != null) {
            for (int i = 0; i < segments.size(); i++) {
                Segment seg = segments.get(i);
                accumulated += seg.getSize();
                if (position < accumulated) {
                    return i;
                }
            }
            return segments.size() - 1;
        }
        return -1;
    }

    public long getSegmentSize(int segmentIndex) {
        if (segments != null && segmentIndex >= 0 && segmentIndex < segments.size()) {
            return segments.get(segmentIndex).getSize();
        }
        return 0L;
    }

    public void setSegment(List<Segment> segment) {
        this.segments = segment;
    }

    public Segment getSegment(int index) {
        return this.segments.get(index);
    }

    public void addSegment(Segment segment) {
        this.segments.add(segment);
    }
}
