package org.model;

import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"group"})
public class Groups {
    @XmlElement(required = true)
    protected List<String> group;

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