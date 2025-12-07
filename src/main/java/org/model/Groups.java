package org.model;

import jakarta.persistence.*;
import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"group"})
public class Groups {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @XmlTransient
    private UUID id;

    @ElementCollection(fetch = FetchType.EAGER)
    @Column(name = "group_name")
    @XmlElement(required = true)
    protected List<String> group;

    public Groups() {
    }

    public List<String> getGroup() {
        if (group == null) {
            group = new ArrayList<>();
        }
        return group;
    }

    public void setGroup(List<String> group) {
        this.group = group;
    }
}