package org.nzbstreamer.model;

import jakarta.persistence.*;
import jakarta.xml.bind.annotation.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One post of an NZB.
 *
 * <p>Groups and segments live directly on this class: groups as a plain element collection,
 * segments as one JSON column instead of a row per segment (see {@link #segments}). Both used to
 * be separate wrapper entities ({@code Groups}, {@code Segments}) with their own tables; a post
 * with tens of thousands of segments made that one insert (or worse, an implicit join table) per
 * segment. A JSON column is one write per post, verified to save and read back correctly at that
 * scale via {@code StreamingCorrectnessCheck}.</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"groups", "segments"})
@Entity
public class NzbFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @XmlTransient
    private UUID id;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "nzb_file_group", joinColumns = @JoinColumn(name = "nzb_file_id"))
    @Column(name = "group_name")
    @XmlElementWrapper(name = "groups")
    @XmlElement(name = "group")
    protected List<String> groups = new ArrayList<>();

    // @Immutable is load-bearing: without it, Hibernate dirty-checks this list element-by-element
    // on every flush. For a post with 100K+ segments that pegs a CPU core for over a minute. This
    // entity's segments are set once at parse time and never mutated in place afterward, so the
    // annotation matches the real usage, not just a workaround.
    @Immutable
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @XmlElementWrapper(name = "segments", required = true)
    @XmlElement(name = "segment")
    protected List<Segment> segments = new ArrayList<>();

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
