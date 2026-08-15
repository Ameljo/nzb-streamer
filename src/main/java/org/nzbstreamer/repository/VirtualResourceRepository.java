package org.nzbstreamer.repository;

import org.nzbstreamer.model.VirtualResource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VirtualResourceRepository extends CrudRepository<VirtualResource, UUID> {

    @Query("SELECT vr FROM VirtualResource vr WHERE vr.path = ?1")
    VirtualResource findByPath(String path);

    @Query("SELECT vr FROM VirtualResource vr LEFT JOIN FETCH vr.children WHERE vr.path = ?1")
    VirtualResource findByPathWithChildren(String path);

    @Query("SELECT vr FROM VirtualResource vr LEFT JOIN FETCH vr.children WHERE vr.id = ?1")
    VirtualResource findByIdWithChildren(UUID id);

    @Query("SELECT vr FROM VirtualResource vr WHERE vr.parent.id = ?1")
    List<VirtualResource> findByParentId(UUID parentId);

    @Query("SELECT vr FROM VirtualResource vr WHERE vr.file.id = ?1")
    VirtualResource findByFileId(UUID fileId);

    @Query("SELECT vr FROM VirtualResource vr WHERE vr.isFolder = false AND vr.file IS NOT NULL")
    List<VirtualResource> findAllFileResources();
}
