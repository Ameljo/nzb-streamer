package org.nzbstreamer.rar.tika;

import org.apache.tika.parser.ParseContext;

import java.io.InputStream;

/**
 * Gives {@link RarHeaderTikaParser} the stream of the caller.
 *
 * <p>Tika gives a {@code TikaInputStream} to each parser. The function {@code skip} of that class
 * reads the bytes with {@code IOUtils.skip}; it does not move the cursor. Thus a walk across a
 * large archive reads all the data of the files. For a stream that gets its bytes from a server,
 * that is the difference between two segments and all the segments.</p>
 *
 * <p>A caller whose stream can move the cursor puts that stream in the {@link ParseContext}:</p>
 *
 * <pre>
 * context.set(RarSourceStream.class, new RarSourceStream(stream));
 * parser.parse(stream, handler, metadata, context);
 * </pre>
 *
 * <p>The parser then reads the headers from that stream. A caller that does not do this gets the
 * same results, but the parser reads the data of the files.</p>
 */
public final class RarSourceStream {

    private final InputStream stream;

    public RarSourceStream(InputStream stream) {
        this.stream = stream;
    }

    public InputStream stream() {
        return stream;
    }
}
