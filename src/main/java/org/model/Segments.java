package org.model;

import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"segment"})
public class Segments {
    @XmlElement(required = true)
    protected List<Segment> segment;

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