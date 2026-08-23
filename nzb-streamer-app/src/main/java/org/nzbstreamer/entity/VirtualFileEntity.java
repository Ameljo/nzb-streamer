package org.nzbstreamer.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The persisted form of a {@link org.nzbstreamer.model.VirtualFile}, the library's plain
 * equivalent. The library's type has no identity of its own; this entity owns the id and the
 * thumbnail reference, both pure application/persistence concerns. See
 * {@code VirtualFileMapper} for the mapping between the two.
 */
@Entity
@Table(name = "virtual_files")
public class VirtualFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String filename;

    private String contentType;

    private Long size;

    /** ID of a thumbnail image {@link VirtualFileEntity} for this file, or {@code null}. */
    @Column(name = "thumbnail_id")
    private UUID thumbnailId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "virtual_file_id")
    @OrderColumn(name = "chunk_order")
    private List<VirtualFileChunkEntity> chunks = new ArrayList<>();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public UUID getThumbnailId() {
        return thumbnailId;
    }

    public void setThumbnailId(UUID thumbnailId) {
        this.thumbnailId = thumbnailId;
    }

    public List<VirtualFileChunkEntity> getChunks() {
        return chunks;
    }

    public void setChunks(List<VirtualFileChunkEntity> chunks) {
        this.chunks = chunks;
    }
}
