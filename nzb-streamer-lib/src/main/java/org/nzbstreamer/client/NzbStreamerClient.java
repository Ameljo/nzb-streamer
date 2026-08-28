package org.nzbstreamer.client;

import org.nzbstreamer.exceptions.NzbParseException;
import org.nzbstreamer.exceptions.UsenetException;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.parser.JaxbNzbParser;
import org.nzbstreamer.service.NzbFileSizeResolver;
import org.nzbstreamer.service.PooledSegmentFetcher;
import org.nzbstreamer.service.SegmentFetcher;
import org.nzbstreamer.service.UsenetConnectionPool;
import org.nzbstreamer.streams.VirtualFileStream;
import org.nzbstreamer.streams.VirtualFileStreamFactory;
import org.nzbstreamer.transformers.TikaNzbFileTransformer;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * A "batteries-included" entry point for parsing NZBs and streaming their content from a Usenet
 * server: give it server credentials, get a working parse-and-stream pipeline with no framework
 * required.
 *
 * <pre>{@code
 * try (NzbStreamerClient client = NzbStreamerClient.forServer(UsenetServerConfig.builder()
 *         .host("news.example.com").port(563).username("user").password("secret").build())) {
 *     Nzb nzb = client.parse(nzbXml);
 *     client.resolveSizes(nzb.getFiles());
 *     for (VirtualFile file : client.buildVirtualFiles(nzb)) {
 *         try (VirtualFileStream stream = client.openStream(file)) {
 *             // read the file
 *         }
 *     }
 * }
 * }</pre>
 */
public final class NzbStreamerClient implements AutoCloseable {

    private final UsenetConnectionPool pool;
    private final JaxbNzbParser parser = new JaxbNzbParser();
    private final NzbFileSizeResolver sizeResolver;
    private final VirtualFileStreamFactory streamFactory;
    private final TikaNzbFileTransformer transformer;

    private NzbStreamerClient(UsenetConnectionPool pool) {
        this.pool = pool;
        SegmentFetcher fetcher = new PooledSegmentFetcher(pool);
        this.sizeResolver = new NzbFileSizeResolver(pool);
        this.streamFactory = new VirtualFileStreamFactory(fetcher);
        this.transformer = new TikaNzbFileTransformer(streamFactory);
    }

    public static NzbStreamerClient forServer(UsenetServerConfig config) {
        return new NzbStreamerClient(UsenetConnectionPool.create(config));
    }

    /** Reads the NZB and makes no connection to the news server. */
    public Nzb parse(InputStream nzbXml) throws NzbParseException {
        return parser.parse(nzbXml);
    }

    /**
     * Gives each post its true size by reading the first article of each post. An NZB's own
     * {@code bytes} attributes give the size of the yEnc-encoded article, not the decoded file, so
     * a caller that needs to seek in a post calls this first.
     */
    public void resolveSizes(List<NzbFile> files)
            throws IOException, InterruptedException, UsenetException {
        sizeResolver.resolve(files);
    }

    /** Reads the headers of every post and builds the files of the NZB, RAR-aware. */
    public List<VirtualFile> buildVirtualFiles(Nzb nzb) {
        return transformer.transform(nzb);
    }

    /** A stream for reading a file in sequence, prefetching whole segments ahead of the reader. */
    public VirtualFileStream open(VirtualFile file) {
        return streamFactory.open(file);
    }

    /** A stream for reading a file in sequence, prefetching decoded chunks ahead of the reader. */
    public VirtualFileStream openStream(VirtualFile file) {
        return streamFactory.openStream(file);
    }

    /** Same as {@link #openStream(VirtualFile)}, with the given chunk size instead of the default. */
    public VirtualFileStream openStream(VirtualFile file, int bufferSize) {
        return streamFactory.openStream(file, bufferSize);
    }

    /** A stream that downloads a segment only when a read needs it, for header-scanning parsers. */
    public VirtualFileStream openRange(VirtualFile file) {
        return streamFactory.openRange(file);
    }

    /** Shuts down the connection pool. A caller closes its client once, when it is done streaming. */
    @Override
    public void close() {
        pool.close();
    }
}
