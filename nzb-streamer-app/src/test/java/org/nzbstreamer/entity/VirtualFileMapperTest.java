package org.nzbstreamer.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.Segment;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.VirtualFileChunk;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Proves {@link VirtualFileMapper} round-trips the library's plain model through this
 * application's JPA entities without losing data, and -- the part a naive per-chunk mapping
 * would get wrong -- without duplicating a post that several chunks (from different files, as
 * two files of one archive use the same post) share.
 */
class VirtualFileMapperTest {

    private static Segment segment(int number, int bytes) {
        Segment segment = new Segment();
        segment.setValue("<seg" + number + "@example.com>");
        segment.setNumber(BigInteger.valueOf(number));
        segment.setBytes(BigInteger.valueOf(bytes));
        segment.setSize(bytes);
        return segment;
    }

    private static NzbFile sharedPost() {
        NzbFile nzbFile = new NzbFile();
        nzbFile.setGroups(List.of("alt.binaries.test"));
        nzbFile.setSegments(List.of(segment(1, 100), segment(2, 100), segment(3, 100)));
        nzbFile.setPoster("tester");
        nzbFile.setDate(1_000_000_000L);
        nzbFile.setSubject("\"archive.rar\" (1/1)");
        nzbFile.setSize(300);
        return nzbFile;
    }

    @Test
    @DisplayName("two files that share a post map to one NzbFileEntity, not two")
    void sharedPostBecomesOneEntity() {
        NzbFile post = sharedPost();
        VirtualFile fileA = new VirtualFile("a.txt", "text/plain",
                List.of(new VirtualFileChunk(post, 0, 0, 150, 0, 1)));
        VirtualFile fileB = new VirtualFile("b.txt", "text/plain",
                List.of(new VirtualFileChunk(post, 0, 150, 150, 1, 2)));

        List<VirtualFileEntity> entities = VirtualFileMapper.toEntities(List.of(fileA, fileB));

        NzbFileEntity postEntityA = entities.get(0).getChunks().get(0).getNzbFile();
        NzbFileEntity postEntityB = entities.get(1).getChunks().get(0).getNzbFile();
        assertSame(postEntityA, postEntityB,
                "both chunks must point at the same NzbFileEntity, the way the original chunks"
                        + " point at the same NzbFile object");
    }

    @Test
    @DisplayName("a file maps to entities and back without losing its bytes or its chunk layout")
    void roundTripPreservesData() {
        NzbFile post = sharedPost();
        VirtualFileChunk chunk = new VirtualFileChunk(post, 0, 0, 300, 0, 2);
        VirtualFile original = new VirtualFile("movie.mkv", "video/x-matroska", List.of(chunk));

        VirtualFileEntity entity = VirtualFileMapper.toEntities(List.of(original)).getFirst();
        VirtualFile roundTripped = VirtualFileMapper.toLib(entity);

        assertEquals(original.getFilename(), roundTripped.getFilename());
        assertEquals(original.getContentType(), roundTripped.getContentType());
        assertEquals(original.getSize(), roundTripped.getSize());
        assertEquals(original.segmentCount(), roundTripped.segmentCount());

        // locate() at every position must give the same segment value as the original -- this is
        // the behavior VirtualFileStream and every SegmentSource actually depend on.
        for (long position = 0; position < original.getSize(); position++) {
            assertEquals(original.locate(position).segment().getValue(),
                    roundTripped.locate(position).segment().getValue(),
                    "position " + position);
        }
    }

    @Test
    @DisplayName("an entity with two chunks sharing a post rebuilds the same sharing on the way back")
    void toLibPreservesSharedPost() {
        NzbFile post = sharedPost();
        VirtualFile fileA = new VirtualFile("a.txt", "text/plain",
                List.of(new VirtualFileChunk(post, 0, 0, 150, 0, 1)));
        VirtualFile fileB = new VirtualFile("b.txt", "text/plain",
                List.of(new VirtualFileChunk(post, 0, 150, 150, 1, 2)));
        List<VirtualFileEntity> entities = VirtualFileMapper.toEntities(List.of(fileA, fileB));

        VirtualFile libA = VirtualFileMapper.toLib(entities.get(0));
        VirtualFile libB = VirtualFileMapper.toLib(entities.get(1));

        assertArrayEquals(
                libA.getChunks().getFirst().getNzbFile().getGroups().toArray(),
                libB.getChunks().getFirst().getNzbFile().getGroups().toArray());
        assertEquals(libA.getChunks().getFirst().group(), libB.getChunks().getFirst().group());
    }
}
