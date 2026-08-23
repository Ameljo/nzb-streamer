package org.nzbstreamer.transformers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.Segment;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.VirtualFileChunk;
import org.nzbstreamer.rar.RarArchive;
import org.nzbstreamer.rar.RarFileEntry;
import org.nzbstreamer.rar.tika.RarArchiveCollector;
import org.nzbstreamer.rar.tika.RarHeaderTikaParser;
import org.nzbstreamer.rar.tika.RarSourceStream;
import org.nzbstreamer.streams.VirtualFileStreamFactory;
import org.nzbstreamer.utils.NzbUtils;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Makes the files of an NZB.
 *
 * <p>Tika reads the first bytes of each post and selects the parser. A RAR archive goes to
 * {@link RarHeaderTikaParser}, which gives the entries of the archive. A different type has no
 * parser, but Tika gives its media type. Thus the name of a post has no importance, and a post
 * with a name of numbers and letters also gets the correct type.</p>
 *
 * <p>This transformer reads all the NZB and not one post, because a file of an archive can
 * continue from one volume to the next volume. It puts the volumes in the sequence of their volume
 * number and joins those entries. One file thus has one chunk for each volume that holds a part of
 * it.</p>
 *
 * <p>Only a media file goes in the result. {@link NzbUtils#isMediaType(String)} makes this
 * decision.</p>
 */
public class TikaNzbFileTransformer implements NzbTransformer<List<VirtualFile>> {

    private static final Logger log = LogManager.getLogger(TikaNzbFileTransformer.class);

    private static final String UNKNOWN_TYPE = "application/octet-stream";

    /** Same reasoning and same bound as {@code NzbFileSizeResolver.PARALLEL_POSTS}: several posts
     *  at a time beats one, and beats opening them all at once. */
    private static final int PARALLEL_POSTS = 12;

    private final Parser parser;
    private final VirtualFileStreamFactory streams;
    private final Tika tika = new Tika();

    public TikaNzbFileTransformer(VirtualFileStreamFactory streams) {
        this(new AutoDetectParser(), streams);
    }

    public TikaNzbFileTransformer(Parser parser, VirtualFileStreamFactory streams) {
        this.parser = parser;
        this.streams = streams;
    }

    /** One post with the headers of its archive. */
    private record Volume(NzbFile nzbFile, String name, RarArchive archive) {
    }

    /** What one post's header read produced: an archive volume, a kept media file, or neither. */
    private record PostOutcome(Volume volume, VirtualFile file) {
        static final PostOutcome NONE = new PostOutcome(null, null);
    }

    @Override
    public List<VirtualFile> transform(Nzb nzb) {
        List<NzbFile> candidates = nzb.getFiles().stream()
                .filter(nzbFile -> {
                    if (NzbUtils.isRepairOrMetadataFile(nzbFile.getSubject())) {
                        log.info("{}: name says repair/metadata file, thus it stays out with no"
                                        + " download", NzbUtils.sanitizeFileName(nzbFile.getSubject()));
                        return false;
                    }
                    return true;
                })
                .toList();

        List<PostOutcome> outcomes = readPosts(candidates);

        List<Volume> archives = new ArrayList<>();
        List<VirtualFile> files = new ArrayList<>();
        for (PostOutcome outcome : outcomes) {
            if (outcome.volume() != null) {
                archives.add(outcome.volume());
            } else if (outcome.file() != null) {
                files.add(outcome.file());
            }
        }

        archives.sort(Comparator.comparingInt(volume -> volume.archive().volumeNumber()));
        files.addAll(joinVolumes(archives));
        return files;
    }

    /**
     * Reads the headers of several posts, a bounded number at a time.
     *
     * <p>Each post costs a network round trip, so one at a time is too slow for an NZB of many
     * posts, and all of them at once risks a news server flagging the burst of new connections.
     * This bounds how many run at the same time instead of either extreme.</p>
     */
    private List<PostOutcome> readPosts(List<NzbFile> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(PARALLEL_POSTS, candidates.size()));
        List<Future<PostOutcome>> tasks = candidates.stream()
                .map(nzbFile -> executor.submit(() -> readPost(nzbFile)))
                .toList();
        try {
            List<PostOutcome> outcomes = new ArrayList<>();
            for (Future<PostOutcome> task : tasks) {
                outcomes.add(task.get());
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tasks.forEach(task -> task.cancel(true));
            throw new RuntimeException("Interrupted while reading the posts", e);
        } catch (ExecutionException e) {
            tasks.forEach(task -> task.cancel(true));
            throw new RuntimeException("Cannot read a post", e.getCause());
        } finally {
            executor.shutdown();
        }
    }

    /** Reads one post's headers and says what it is: an archive volume, a kept media file, or
     *  neither. */
    private PostOutcome readPost(NzbFile nzbFile) {
        String name = NzbUtils.sanitizeFileName(nzbFile.getSubject());
        RarArchiveCollector collector = new RarArchiveCollector();
        ParseContext context = new ParseContext();
        context.set(RarArchiveCollector.class, collector);

        Metadata metadata = new Metadata();
        // A hint for Tika. The content has more authority than this name.
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);

        VirtualFile postedFile = new VirtualFile(nzbFile.getSize(), name, nzbFile);
        long startedAt = System.nanoTime();
        try (InputStream stream = streams.openRange(postedFile)) {
            // The stream of a virtual file can move its cursor. The parser must use this
            // stream and not the stream of Tika, because TikaInputStream.skip reads the bytes.
            context.set(RarSourceStream.class, new RarSourceStream(stream));
            parser.parse(stream, new DefaultHandler(), metadata, context);
        } catch (Exception e) {
            log.error("Cannot read {}: {}", name, e.getMessage());
            return PostOutcome.NONE;
        }
        log.info("{}: header read in {} ms", name, (System.nanoTime() - startedAt) / 1_000_000);

        RarArchive archive = collector.archive();
        if (archive != null) {
            return new PostOutcome(new Volume(nzbFile, name, archive), null);
        }
        return singleFile(postedFile, name, metadata.get(Metadata.CONTENT_TYPE))
                .map(file -> new PostOutcome(null, file))
                .orElse(PostOutcome.NONE);
    }

    /** Gives the file of a post that is not an archive. All the bytes of the post are the file. */
    private java.util.Optional<VirtualFile> singleFile(VirtualFile postedFile, String name,
                                                       String contentType) {
        String type = contentType == null ? UNKNOWN_TYPE : contentType;
        if (!NzbUtils.isMediaType(type)) {
            log.info("{}: type {} is not a media type, thus the file stays out", name, type);
            return java.util.Optional.empty();
        }
        postedFile.setContentType(type);
        log.info("{}: {}, {} bytes", name, type, postedFile.getSize());
        return java.util.Optional.of(postedFile);
    }

    /**
     * Joins the entries of the volumes.
     *
     * <p>Only the last entry of a volume can continue in the next volume. Thus one file is open at
     * a given time.</p>
     */
    private List<VirtualFile> joinVolumes(List<Volume> volumes) {
        List<VirtualFile> files = new ArrayList<>();

        String openName = null;
        long openSize = 0;
        List<VirtualFileChunk> openChunks = new ArrayList<>();

        for (Volume volume : volumes) {
            for (RarFileEntry entry : volume.archive().entries()) {
                if (entry.directory() || !entry.stored() || entry.encrypted()) {
                    log.info("{}: entry {} stays out (directory={}, method=m{}, encrypted={})",
                            volume.name(), entry.name(), entry.directory(), entry.method(),
                            entry.encrypted());
                    continue;
                }

                VirtualFileChunk chunk = chunkOf(volume.nzbFile(), entry,
                        entry.splitBefore() ? openSize : 0);

                if (entry.splitBefore()) {
                    if (openName == null || !openName.equals(entry.name())) {
                        log.warn("{}: entry {} continues from a volume that is not here",
                                volume.name(), entry.name());
                        continue;
                    }
                    openChunks.add(chunk);
                    openSize += chunk.getLength();
                } else {
                    openName = entry.name();
                    openChunks = new ArrayList<>(List.of(chunk));
                    openSize = chunk.getLength();
                }

                if (!entry.splitAfter()) {
                    if (openSize != entry.unpackedSize()) {
                        log.warn("{}: the chunks give {} bytes but the header gives {}", openName,
                                openSize, entry.unpackedSize());
                    }
                    String type = tika.detect(openName);
                    if (NzbUtils.isMediaType(type)) {
                        files.add(new VirtualFile(openName, type, openChunks));
                        log.info("{}: {}, {} chunks, {} bytes", openName, type, openChunks.size(),
                                openSize);
                    } else {
                        log.info("{}: type {} is not a media type, thus the file stays out",
                                openName, type);
                    }
                    openName = null;
                    openChunks = new ArrayList<>();
                    openSize = 0;
                }
            }
        }

        if (openName != null) {
            log.warn("{}: the last volume is not here, thus this file stays out", openName);
        }
        return files;
    }

    /**
     * Makes the chunk of one entry in one volume. It finds the segments that hold the bytes from
     * {@code dataOffset} to {@code dataOffset + packedSize}. Those segments are always in
     * sequence.
     */
    private VirtualFileChunk chunkOf(NzbFile nzbFile, RarFileEntry entry, long fileStart) {
        List<Segment> segments = nzbFile.getSegments();
        long from = entry.dataOffset();
        long to = from + entry.packedSize();

        int firstSegment = -1;
        int lastSegment = -1;
        long offsetInFirst = 0;
        long position = 0;

        for (int i = 0; i < segments.size(); i++) {
            long segmentEnd = position + segments.get(i).getSize();
            if (segmentEnd > from && position < to) {
                if (firstSegment < 0) {
                    firstSegment = i;
                    offsetInFirst = from - position;
                }
                lastSegment = i;
            }
            position = segmentEnd;
            if (position >= to) {
                break;
            }
        }
        return new VirtualFileChunk(nzbFile, fileStart, offsetInFirst, entry.packedSize(),
                firstSegment, lastSegment);
    }
}
