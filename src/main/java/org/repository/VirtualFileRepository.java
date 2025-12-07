package org.repository;

import org.model.VirtualFile;
import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

public interface VirtualFileRepository extends CrudRepository<VirtualFile, UUID> {
}
