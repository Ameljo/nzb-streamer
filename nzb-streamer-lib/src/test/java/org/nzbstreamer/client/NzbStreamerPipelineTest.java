package org.nzbstreamer.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nzbstreamer.exceptions.NzbParseException;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.parser.SaxNzbParser;
import org.nzbstreamer.service.SegmentFetcher;
import org.nzbstreamer.streams.VirtualFileStream;
import org.nzbstreamer.streams.VirtualFileStreamFactory;
import org.nzbstreamer.transformers.TikaNzbFileTransformer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the pipeline that {@link NzbStreamerClient} wires together -- parse, build the
 * VirtualFiles (RAR/media-aware, over Tika), and open a stream -- works end to end with a fake
 * {@link SegmentFetcher} standing in for the network, and zero Spring on the classpath. This is
 * the same wiring {@link NzbStreamerClient#forServer(UsenetServerConfig)} does with a real
 * {@code UsenetConnectionPool}; this test exercises the pieces directly since a fake fetcher
 * cannot be plugged into a pool-backed client.
 */
class NzbStreamerPipelineTest {

    /** A minimal JPEG: Tika detects "image/jpeg" from the SOI + APP0 magic bytes alone. */
    private static final byte[] JPEG_BYTES = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
    };

    private static final String MESSAGE_ID = "abc123@example.com";
    private static final String GROUP = "alt.binaries.test";

    private static final String NZB_XML = """
            <?xml version="1.0" encoding="iso-8859-1"?>
            <nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">
              <file poster="tester" date="1000000000" subject="&quot;photo.jpg&quot; (1/1)">
                <groups><group>%s</group></groups>
                <segments><segment bytes="%d" number="1">%s</segment></segments>
              </file>
            </nzb>
            """.formatted(GROUP, JPEG_BYTES.length, MESSAGE_ID);

    /** Gives the fixed JPEG bytes for any segment, standing in for a real download. */
    private static final class FakeFetcher implements SegmentFetcher {

        @Override
        public byte[] fetchPrefix(String messageId, String group, int maxBytes) {
            return JPEG_BYTES;
        }

        @Override
        public void fetch(String messageId, String group, BlockingQueue<byte[]> buffer,
                           int bufferSize, int skip, int trim) throws InterruptedException {
            int end = Math.min(skip + trim, JPEG_BYTES.length);
            buffer.put(skip < end ? Arrays.copyOfRange(JPEG_BYTES, skip, end) : new byte[0]);
        }
    }

    @Test
    @DisplayName("parse, build the VirtualFiles, and stream them give back the original bytes")
    void parseBuildAndStreamRoundTrips() throws IOException, NzbParseException {
        SegmentFetcher fetcher = new FakeFetcher();
        VirtualFileStreamFactory streamFactory = new VirtualFileStreamFactory(fetcher);
        TikaNzbFileTransformer transformer = new TikaNzbFileTransformer(streamFactory);

        Nzb nzb = new SaxNzbParser().parse(
                new ByteArrayInputStream(NZB_XML.getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(1, nzb.getFiles().size(), "the NZB has one post");
        assertEquals(JPEG_BYTES.length, nzb.getFile(0).getSize(),
                "the size comes from the segment's bytes attribute, with no network call");

        List<VirtualFile> files = transformer.transform(nzb);
        assertEquals(1, files.size(), "the JPEG is a media file, thus it is kept");
        assertEquals("image/jpeg", files.getFirst().getContentType());

        byte[] actual;
        try (VirtualFileStream stream = streamFactory.openStream(files.getFirst())) {
            actual = stream.readAllBytes();
        }
        assertArrayEquals(JPEG_BYTES, actual, "the stream must give back the original bytes");
    }
}
