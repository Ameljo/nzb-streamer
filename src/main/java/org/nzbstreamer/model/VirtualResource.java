package org.nzbstreamer.model;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BM: added reportable so that all these resource classes work with REPORT
 *
 * @author alex
 */
@Entity
@Table(indexes = {
        @Index(name = "idx_virtual_resource_path", columnList = "path")})
public class VirtualResource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true)
    private String path;

    private boolean isFolder;

    private String name;

    @OneToOne(cascade = CascadeType.MERGE)
    private VirtualFile file;

    @ManyToOne(cascade = CascadeType.ALL)
    private VirtualResource parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VirtualResource> children = new ArrayList<>();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isFolder() {
        return isFolder;
    }

    public void setFolder(boolean folder) {
        isFolder = folder;
    }

    public VirtualFile getFile() {
        return file;
    }

    public void setFile(VirtualFile file) {
        this.file = file;
    }

    public List<VirtualResource> getChildren() {
        return children;
    }

    public void setChildren(List<VirtualResource> children) {
        this.children = children;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public VirtualResource getParent() {
        return parent;
    }

    public void setParent(VirtualResource parent) {
        this.parent = parent;
    }
}