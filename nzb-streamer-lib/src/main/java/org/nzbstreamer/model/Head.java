package org.nzbstreamer.model;

import java.util.List;
import java.util.ArrayList;

public class Head {

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
