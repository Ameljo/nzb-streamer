package org.model;

import jakarta.persistence.*;
import jakarta.xml.bind.annotation.*;

import java.util.UUID;

@Entity
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = {"value"})
public class Meta {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @XmlTransient
    private UUID id;
    @XmlValue
    protected String value;
    @XmlAttribute(name = "type", required = true)
    protected String type;

    public Meta() {
    }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}

