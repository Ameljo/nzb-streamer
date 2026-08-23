package org.nzbstreamer.repository;

import org.nzbstreamer.entity.VirtualFileEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface VirtualFileRepository extends CrudRepository<VirtualFileEntity, UUID> {
}
