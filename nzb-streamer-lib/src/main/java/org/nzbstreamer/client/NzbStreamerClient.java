package org.nzbstreamer.client;

import org.nzbstreamer.exceptions.NzbParseException;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.parser.SaxNzbParser;
import org.nzbstreamer.service.CachingSegmentFetcher;
import org.nzbstreamer.service.NzbFileSizeResolver;
import org.nzbstreamer.service.PooledSegmentFetcher;
import org.nzbstreamer.service.SegmentCache;
import org.nzbstreamer.service.SegmentFetcher;
import org.nzbstreamer.service.UsenetConnectionPool;
import org.nzbstreamer.streams.VirtualFileStream;
import org.nzbstreamer.streams.VirtualFileStreamFactory;
import org.nzbstreamer.transformers.TikaNzbFileTransformer;
import org.nzbstreamer.utils.NzbUtils;

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
    private final SaxNzbParser parser = new SaxNzbParser();
    private final NzbFileSizeResolver sizeResolver;
    private final VirtualFileStreamFactory streamFactory;
    private final TikaNzbFileTransformer transformer;

    private NzbStreamerClient(UsenetConnectionPool pool) {
        this.pool = pool;
        SegmentFetcher fetcher = new CachingSegmentFetcher(new PooledSegmentFetcher(pool),
                new SegmentCache(200L * 1024 * 1024));
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
     * Reads the headers of every post and builds the files of the NZB, RAR-aware.
     *
     * <p>A repair/metadata post (PAR2, SFV, NFO, ...) never becomes a file, so its size is never
     * resolved either. A post whose true size cannot be resolved does not become a file: a caller
     * cannot seek in a file with a wrong size, so it is left out. Every other post in the NZB is
     * still built normally.</p>
     */
    public List<VirtualFile> buildVirtualFiles(Nzb nzb) {
        List<NzbFile> candidates = nzb.getFiles().stream()
                .filter(file -> !NzbUtils.isRepairOrMetadataFile(file.getSubject()))
                .toList();
        List<NzbFile> sized = sizeResolver.resolve(candidates);

        Nzb resolvedNzb = new Nzb();
        resolvedNzb.setHead(nzb.getHead());
        resolvedNzb.setFiles(sized);
        return transformer.transform(resolvedNzb);
    }

    /** A stream for reading a file in sequence, prefetching whole segments ahead of the reader. */
    public VirtualFileStream open(VirtualFile file) {
        return streamFactory.open(file);
    }

    /** A stream for reading a file in sequence, prefetching decoded chunks ahead of the reader. */
    public VirtualFileStream openStream(VirtualFile file) {
        return streamFactory.openStream(file);
    }

    public VirtualFileStream openDynamic(VirtualFile file) {
        return streamFactory.openDynamic(file);
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
