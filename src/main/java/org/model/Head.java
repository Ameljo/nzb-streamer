package org.model;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import jakarta.persistence.*;
import jakarta.xml.bind.annotation.*;

@Entity
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"meta"})
public class Head {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @XmlTransient
    private UUID id;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    protected List<Meta> meta;

    public Head() {
    }

    public List<Meta> getMeta() {
        if (meta == null) {
            meta = new ArrayList<>();
        }
        return meta;
    }

    public void setMeta(List<Meta> meta) {
        this.meta = meta;
    }
}
