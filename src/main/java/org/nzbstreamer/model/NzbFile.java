package org.nzbstreamer.model;

import jakarta.persistence.*;
import jakarta.xml.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"groups", "segments"})
@Entity
public class NzbFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @XmlTransient
    private UUID id;

    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    protected Groups groups;

    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    @XmlElement(required = true)
    protected Segments segments;
    @XmlAttribute(name = "poster")
    protected String poster;
    @XmlAttribute(name = "date")
    protected Long date;
    @XmlAttribute(name = "subject")
    protected String subject;

    @XmlTransient
    private long size;

    public NzbFile() {
    }

    public Groups getGroups() { return groups; }
    public void setGroups(Groups groups) { this.groups = groups; }
    public Segments getSegments() { return segments; }
    public void setSegments(Segments segments) { this.segments = segments; }
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
        if (getSegments() != null && getSegments().getSegment() != null) {
            for (Segment segment : getSegments().getSegment()) {
                if (segment.getBytes() != null) {
                    total += segment.getBytes().longValue();
                }
            }
        }
        return total;
    }

    public int getSegmentAtPosition(long position) {
        long accumulated = 0L;
        if (getSegments() != null && getSegments().getSegment() != null) {
            for (int i = 0; i < getSegments().getSegment().size(); i++) {
                Segment seg = getSegments().getSegment().get(i);
                accumulated += seg.getSize();
                if (position < accumulated) {
                    return i;
                }
            }
            return getSegments().getSegment().size() - 1;
        }
        return -1;
    }

    public long getSegmentSize(int segmentIndex) {
        if (getSegments() != null && getSegments().getSegment() != null && segmentIndex >= 0 && segmentIndex < getSegments().getSegment().size()) {
            return getSegments().getSegment().get(segmentIndex).getSize();
        }
        return 0L;
    }

    public void setSegment(List<Segment> segment) {
        this.segments.setSegment(segment);
    }

    public Segment getSegment(int index) {
        return this.segments.getSegment().get(index);
    }

    public void addSegment(Segment segment) {
        this.segments.getSegment().add(segment);
    }
}
