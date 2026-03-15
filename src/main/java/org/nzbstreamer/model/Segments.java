package org.nzbstreamer.model;

import jakarta.persistence.*;
import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"segment"})
public class Segments {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @XmlTransient
    private UUID id;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @XmlElement(required = true)
    protected List<Segment> segment;

    public Segments() {
    }

    public List<Segment> getSegment() {
        if (segment == null) {
            segment = new ArrayList<>();
        }
        return segment;
    }

    public void setSegment(List<Segment> segment) {
        this.segment = segment;
    }
}