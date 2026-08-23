package org.nzbstreamer.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.nzbstreamer.model.Segment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The persisted form of one post of an NZB. Mirrors {@link org.nzbstreamer.model.NzbFile}, the
 * library's plain equivalent -- the library has no persistence concern of its own, so the app
 * owns identity and storage here and maps to/from the plain type at the edges (see
 * {@code VirtualFileMapper}).
 *
 * <p>Segments are one JSON column instead of a row per segment (see {@link #segments}): a post
 * with tens of thousands of segments made that one insert (or worse, an implicit join table) per
 * segment. A JSON column is one write per post.</p>
 */
@Entity
@Table(name = "nzb_file")
public class NzbFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "nzb_file_group", joinColumns = @JoinColumn(name = "nzb_file_id"))
    @Column(name = "group_name")
    private List<String> groups = new ArrayList<>();

    // @Immutable is load-bearing: without it, Hibernate dirty-checks this list element-by-element
    // on every flush. For a post with 100K+ segments that pegs a CPU core for over a minute. This
    // entity's segments are set once at save time and never mutated in place afterward, so the
    // annotation matches the real usage, not just a workaround.
    @Immutable
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Segment> segments = new ArrayList<>();

    private String poster;
    private Long date;
    private String subject;
    private long size;

    public UUID getId() {
        return id;
    }

    public List<String> getGroups() {
        return groups;
    }

    public void setGroups(List<String> groups) {
        this.groups = groups;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public void setSegments(List<Segment> segments) {
        this.segments = segments;
    }

    public String getPoster() {
        return poster;
    }

    public void setPoster(String poster) {
        this.poster = poster;
    }

    public Long getDate() {
        return date;
    }

    public void setDate(Long date) {
        this.date = date;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }
}
