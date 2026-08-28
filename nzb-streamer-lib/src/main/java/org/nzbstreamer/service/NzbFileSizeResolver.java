package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.decoder.records.YencHeader;
import org.nzbstreamer.decoder.records.YencPartInfo;
import org.nzbstreamer.exceptions.UsenetException;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.Segment;
import org.nzbstreamer.utils.NzbUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Gives the segments of a post their size and their position in the file.
 *
 * <p>The attribute {@code bytes} of an NZB gives the size of the article, which holds the yEnc
 * data and not the bytes of the file. This class reads the first article of the post instead: its
 * {@code =ybegin} line gives the size of the file and its {@code =ypart} line gives the size of
 * one segment. All the segments have that size, except the last one, which holds what stays.</p>
 *
 * <p>The class reads the two first lines of an article and stops there. Thus it takes a connection
 * of {@link UsenetConnectionPool} and closes it: the answer of the server did not arrive at its
 * end, and the connection cannot take another command.</p>
 */
public class NzbFileSizeResolver {

    private static final Logger log = LogManager.getLogger(NzbFileSizeResolver.class);

    /**
     * A post at a time was too slow for an NZB of many posts (each one costs a network round
     * trip), and all of them at once risks the server flagging the burst of new connections. This
     * is the compromise: several posts at a time, capped well under the size of the connection
     * pool.
     */
    private static final int PARALLEL_POSTS = 12;

    private final UsenetConnectionPool pool;

    public NzbFileSizeResolver(UsenetConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * Gives each post its true size, a bounded number at a time, and gives back only the posts
     * that could be read. A post whose article is missing or unusable does not stop the others,
     * and it does not become a file with a guessed size either: a wrong size breaks a seek in
     * that file for whatever plays it, so the post is left out instead.
     */
    public List<NzbFile> resolve(List<NzbFile> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(PARALLEL_POSTS, files.size()));
        List<Future<NzbFile>> tasks = files.stream()
                .map(file -> executor.submit(() -> resolveOrDrop(file)))
                .toList();
        try {
            List<NzbFile> resolved = new ArrayList<>();
            for (Future<NzbFile> task : tasks) {
                NzbFile file = task.get();
                if (file != null) {
                    resolved.add(file);
                }
            }
            return resolved;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tasks.forEach(task -> task.cancel(true));
            throw new RuntimeException("Interrupted while resolving sizes", e);
        } catch (ExecutionException e) {
            // resolveOrDrop() catches everything itself; a task should never fail here.
            throw new IllegalStateException("Unexpected failure while resolving sizes", e.getCause());
        } finally {
            executor.shutdown();
        }
    }

    /** Resolves one post's true size, or returns null when the post cannot be read. */
    private NzbFile resolveOrDrop(NzbFile file) {
        try {
            resolve(file);
            return file;
        } catch (Exception e) {
            log.warn("{}: could not resolve the true size, thus it stays out with no download: {}",
                    NzbUtils.sanitizeFileName(file.getSubject()), e.toString());
            return null;
        }
    }

    /** Gives each segment of one post its size and its position. */
    public void resolve(NzbFile file) throws IOException, InterruptedException, UsenetException {
        long startedAt = System.nanoTime();
        List<Segment> segments = file.getSegments();
        String messageId = NzbUtils.normalizeMessageId(segments.getFirst().getValue());
        String group = file.getGroups().getFirst();

        PooledClient pooled = pool.borrow(group);
        YencStart start;
        try {
            start = readStart(pooled.retrieveArticle(messageId));
        } finally {
            // The operation read the two first lines of the article only, thus the answer of the
            // server did not arrive at its end and the connection cannot take another command.
            // The pool opens a new one when a caller asks for it.
            pool.discard(pooled);
        }

        if (start.header() == null) {
            throw new IOException("No yEnc header in " + messageId);
        }
        long total = start.header().size();
        long segmentSize = start.part() == null
                ? total : start.part().end() - start.part().begin() + 1;
        int count = segments.size();
        long last = total - segmentSize * (count - 1);
        if (segmentSize <= 0 || last <= 0) {
            throw new IOException("The sizes of " + messageId + " are not usable: total " + total
                    + ", segment " + segmentSize + ", " + count + " segments");
        }

        long position = 0;
        for (int i = 0; i < count; i++) {
            Segment segment = segments.get(i);
            segment.setSize(i == count - 1 ? last : segmentSize);
            segment.setStartPosition(position);
            position += segment.getSize();
        }
        file.setSize(total);
        log.debug("sizes of {}: {} segments of {} bytes, last {}, total {}, article {} in {} ms",
                NzbUtils.sanitizeFileName(file.getSubject()), count, segmentSize, last, total,
                messageId, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private record YencStart(YencHeader header, YencPartInfo part) {}

    /** Reads the ybegin line and the ypart line of an article, and stops there. */
    private YencStart readStart(Reader reader) throws IOException {
        YencHeader header = null;
        YencPartInfo part = null;
        BufferedReader lines = new BufferedReader(reader);
        String line;
        while ((line = lines.readLine()) != null) {
            if (header == null && line.startsWith("=ybegin")) {
                header = YencHeader.parse(line);
            } else if (header != null) {
                // The line after ybegin is the ypart line, or the first line of the data when the
                // article holds all the file. Both mean that the answer holds nothing more.
                if (line.startsWith("=ypart")) {
                    part = YencPartInfo.parse(line);
                }
                break;
            }
        }
        return new YencStart(header, part);
    }
}
