package org.model;

import java.util.List;
import java.util.ArrayList;
import jakarta.xml.bind.annotation.*;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"meta"})
public class Head {
    protected List<Meta> meta;

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
