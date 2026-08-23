package org.nzbstreamer.rar.tika;

import org.apache.tika.parser.ParseContext;
import org.nzbstreamer.rar.RarArchive;

/**
 * Gives the {@link RarArchive} object to the caller after a Tika parse operation.
 *
 * <p>Tika puts its results in {@code Metadata}. All the values in {@code Metadata} are character
 * strings. A caller that needs the offsets as numbers puts a collector in the
 * {@link ParseContext} before the parse operation:</p>
 *
 * <pre>
 * RarArchiveCollector collector = new RarArchiveCollector();
 * ParseContext context = new ParseContext();
 * context.set(RarArchiveCollector.class, collector);
 * parser.parse(stream, handler, metadata, context);
 * RarArchive archive = collector.archive();
 * </pre>
 *
 * <p>A caller that does not set a collector gets all the metadata and the XHTML data.</p>
 */
public final class RarArchiveCollector {

    private RarArchive archive;

    public RarArchive archive() {
        return archive;
    }

    void set(RarArchive archive) {
        this.archive = archive;
    }
}
