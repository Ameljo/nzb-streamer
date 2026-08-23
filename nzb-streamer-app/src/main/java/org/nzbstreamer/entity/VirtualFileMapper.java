package org.nzbstreamer.entity;

import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.Segment;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.VirtualFileChunk;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps between the library's plain {@link VirtualFile}/{@link NzbFile} and this application's
 * JPA entities. The library has no persistence concern of its own (see
 * {@link org.nzbstreamer.entity}'s package docs); this is the one place that translates between
 * the two.
 *
 * <p>Several chunks of a {@link VirtualFile}, or of different files from the same NZB, can share
 * the same post (two files of one archive use the same post). {@link TikaNzbFileTransformer}
 * keeps that sharing as one Java object reference; {@link #toEntities} preserves it as one
 * {@link NzbFileEntity} row that several {@link VirtualFileChunkEntity} rows point at, and
 * {@link #toLib} rebuilds the same sharing in reverse.</p>
 */
public final class VirtualFileMapper {

    private VirtualFileMapper() {
    }

    /** Builds unsaved entities for a batch of files, ready to pass to a repository. */
    public static List<VirtualFileEntity> toEntities(List<VirtualFile> files) {
        Map<NzbFile, NzbFileEntity> posts = new IdentityHashMap<>();
        List<VirtualFileEntity> entities = new ArrayList<>();
        for (VirtualFile file : files) {
            entities.add(toEntity(file, posts));
        }
        return entities;
    }

    private static VirtualFileEntity toEntity(VirtualFile file, Map<NzbFile, NzbFileEntity> posts) {
        VirtualFileEntity entity = new VirtualFileEntity();
        entity.setFilename(file.getFilename());
        entity.setContentType(file.getContentType());
        entity.setSize(file.getSize());

        List<VirtualFileChunkEntity> chunks = new ArrayList<>();
        for (VirtualFileChunk chunk : file.getChunks()) {
            NzbFileEntity postEntity = posts.computeIfAbsent(chunk.getNzbFile(),
                    VirtualFileMapper::toEntity);

            VirtualFileChunkEntity chunkEntity = new VirtualFileChunkEntity();
            chunkEntity.setNzbFile(postEntity);
            chunkEntity.setFileStart(chunk.getFileStart());
            chunkEntity.setOffset(chunk.getOffset());
            chunkEntity.setLength(chunk.getLength());
            chunkEntity.setFirstSegment(chunk.getFirstSegment());
            chunkEntity.setLastSegment(chunk.getLastSegment());
            chunks.add(chunkEntity);
        }
        entity.setChunks(chunks);
        return entity;
    }

    private static NzbFileEntity toEntity(NzbFile nzbFile) {
        NzbFileEntity entity = new NzbFileEntity();
        entity.setGroups(new ArrayList<>(nzbFile.getGroups()));
        entity.setSegments(new ArrayList<>(nzbFile.getSegments()));
        entity.setPoster(nzbFile.getPoster());
        entity.setDate(nzbFile.getDate());
        entity.setSubject(nzbFile.getSubject());
        entity.setSize(nzbFile.getSize());
        return entity;
    }

    /**
     * Builds the plain, library-facing file that {@code VirtualFileStreamFactory} and
     * {@code NzbStreamerClient} operate on. The entity's lazy {@code chunks} collection must
     * already be initialized (loaded inside a session/transaction) before calling this --
     * otherwise this throws {@code LazyInitializationException} outside a session, or silently
     * builds an incomplete file inside one that already closed its collection proxy.
     */
    public static VirtualFile toLib(VirtualFileEntity entity) {
        Map<NzbFileEntity, NzbFile> posts = new IdentityHashMap<>();
        List<VirtualFileChunk> chunks = new ArrayList<>();
        for (VirtualFileChunkEntity chunkEntity : entity.getChunks()) {
            NzbFile nzbFile = posts.computeIfAbsent(chunkEntity.getNzbFile(),
                    VirtualFileMapper::toLib);
            chunks.add(new VirtualFileChunk(nzbFile, chunkEntity.getFileStart(),
                    chunkEntity.getOffset(), chunkEntity.getLength(),
                    chunkEntity.getFirstSegment(), chunkEntity.getLastSegment()));
        }
        return new VirtualFile(entity.getFilename(), entity.getContentType(), chunks);
    }

    private static NzbFile toLib(NzbFileEntity entity) {
        NzbFile nzbFile = new NzbFile();
        nzbFile.setGroups(entity.getGroups());
        List<Segment> segments = entity.getSegments();
        nzbFile.setSegments(segments);
        nzbFile.setPoster(entity.getPoster());
        nzbFile.setDate(entity.getDate());
        nzbFile.setSubject(entity.getSubject());
        nzbFile.setSize(entity.getSize());
        return nzbFile;
    }
}
