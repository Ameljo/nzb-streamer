package org.nzbstreamer.rar.tika;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nzbstreamer.rar.RarArchive;
import org.nzbstreamer.rar.RarFileEntry;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Tika parser. These tests examine the media types, the registration of the parser, the
 * metadata, the XHTML data and the object that the {@link ParseContext} gives to the caller.
 */
class RarHeaderTikaParserTest {

    private static InputStream fixture(String name) {
        InputStream in = RarHeaderTikaParserTest.class.getResourceAsStream("/rar/" + name);
        assertNotNull(in, "missing test fixture /rar/" + name);
        return in;
    }

    @Test
    @DisplayName("declares the RAR media types Tika detects")
    void supportsRarMediaTypes() {
        var supported = new RarHeaderTikaParser().getSupportedTypes(new ParseContext());

        assertTrue(supported.contains(MediaType.parse("application/x-rar-compressed")), "" + supported);
        assertTrue(supported.contains(MediaType.parse("application/x-rar-compressed;version=4")), "" + supported);
        assertTrue(supported.contains(MediaType.parse("application/x-rar-compressed;version=5")), "" + supported);
    }

    @Test
    @DisplayName("fills metadata with one indexed block per entry")
    void populatesMetadata() throws Exception {
        Metadata metadata = new Metadata();

        try (InputStream in = fixture("rar5-multi.rar")) {
            new RarHeaderTikaParser().parse(in, new BodyContentHandler(), metadata, new ParseContext());
        }

        assertEquals("RAR5", metadata.get(RarMetadata.FORMAT));
        assertEquals("false", metadata.get(RarMetadata.VOLUME));
        assertEquals("true", metadata.get(RarMetadata.END_OF_ARCHIVE));
        assertEquals("4", metadata.get(RarMetadata.ENTRY_COUNT));

        assertEquals("movie.mkv", metadata.get(RarMetadata.entryKey(0, RarMetadata.ENTRY_NAME)));
        assertEquals("8192", metadata.get(RarMetadata.entryKey(0, RarMetadata.ENTRY_PACKED_SIZE)));
        assertEquals("true", metadata.get(RarMetadata.entryKey(0, RarMetadata.ENTRY_STORED)));
        assertEquals("251AD45A", metadata.get(RarMetadata.entryKey(0, RarMetadata.ENTRY_CRC32)),
                "CRC32 as recorded by the archiver");
        assertEquals("true", metadata.get(RarMetadata.entryKey(3, RarMetadata.ENTRY_DIRECTORY)));

        long bytesRead = Long.parseLong(metadata.get(RarMetadata.BYTES_READ));
        assertTrue(bytesRead < 1024, "should report a small read count, was " + bytesRead);
    }

    @Test
    @DisplayName("writes the entries into the XHTML body")
    void writesXhtml() throws Exception {
        BodyContentHandler handler = new BodyContentHandler();

        try (InputStream in = fixture("rar5-multi.rar")) {
            new RarHeaderTikaParser().parse(in, handler, new Metadata(), new ParseContext());
        }

        String body = handler.toString();
        assertTrue(body.contains("movie.mkv"), body);
        assertTrue(body.contains("subs/movie.srt"), body);
        assertTrue(body.contains("dataOffset="), body);
    }

    @Test
    @DisplayName("hands back the typed archive through the parse context")
    void collectorReceivesTypedResult() throws Exception {
        RarArchiveCollector collector = new RarArchiveCollector();
        ParseContext context = new ParseContext();
        context.set(RarArchiveCollector.class, collector);

        try (InputStream in = fixture("rar5-multi.rar")) {
            new RarHeaderTikaParser().parse(in, new BodyContentHandler(), new Metadata(), context);
        }

        RarArchive archive = collector.archive();
        assertNotNull(archive, "the collector should have been filled");
        assertEquals(List.of("movie.mkv", "movie.nfo", "subs/movie.srt", "subs"),
                archive.entries().stream().map(RarFileEntry::name).toList());
        assertEquals(3, archive.streamableEntries().size(), "the directory is not streamable");
    }

    /**
     * A stream that fails when a caller reads a byte of the data of a file. Only skip is permitted
     * across those bytes.
     */
    private static final class PayloadGuardStream extends java.io.InputStream {

        private final byte[] data;
        private final List<long[]> forbidden;
        private long position;
        private int readCalls;

        PayloadGuardStream(byte[] data, List<long[]> forbidden) {
            this.data = data;
            this.forbidden = forbidden;
        }

        private void checkNotPayload(long from, long length) {
            for (long[] range : forbidden) {
                if (Math.max(from, range[0]) < Math.min(from + length, range[0] + range[1])) {
                    throw new AssertionError("the parser read the data of a file at offset " + from
                            + "; TikaInputStream.skip reads the bytes, thus the parser must use the"
                            + " stream of RarSourceStream");
                }
            }
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            checkNotPayload(position, 1);
            readCalls++;
            return data[(int) position++] & 0xFF;
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            int count = (int) Math.min(length, data.length - position);
            checkNotPayload(position, count);
            readCalls++;
            System.arraycopy(data, (int) position, destination, offset, count);
            position += count;
            return count;
        }

        @Override
        public long skip(long count) {
            long actual = Math.min(count, data.length - position);
            position += actual;
            return actual;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public synchronized void mark(int readLimit) {
        }

        @Override
        public synchronized void reset() {
            position = 0;
        }
    }

    @Test
    @DisplayName("the parser uses the stream of the caller, thus the data of the files stays unread")
    void usesTheStreamOfTheCallerAndSkipsTheData() throws Exception {
        byte[] data;
        try (InputStream in = fixture("rar5-multi.rar")) {
            data = in.readAllBytes();
        }

        List<long[]> payloads = new java.util.ArrayList<>();
        for (var block : new org.nzbstreamer.rar.RarHeaderParser()
                .parse(new java.io.ByteArrayInputStream(data)).blocks()) {
            if (block.dataSize() > 0) {
                payloads.add(new long[]{block.dataOffset(), block.dataSize()});
            }
        }

        RarArchiveCollector collector = new RarArchiveCollector();
        ParseContext context = new ParseContext();
        context.set(RarArchiveCollector.class, collector);

        // Both streams read the same bytes. The TikaInputStream is the stream of the parameter,
        // and its skip reads the data. The guard is the stream of the caller, and its skip moves
        // the cursor. A parser that uses the wrong one reads the data and fails here.
        PayloadGuardStream guard = new PayloadGuardStream(data, payloads);
        context.set(RarSourceStream.class, new RarSourceStream(guard));

        try (org.apache.tika.io.TikaInputStream tikaStream =
                     org.apache.tika.io.TikaInputStream.get(guard)) {
            new RarHeaderTikaParser().parse(tikaStream, new BodyContentHandler(), new Metadata(),
                    context);
        }

        assertNotNull(collector.archive(), "the parser must have read the headers");
        assertEquals(4, collector.archive().entries().size());
        assertTrue(guard.readCalls > 0, "the parser must read the headers from the guard stream");
    }

    @Test
    @DisplayName("Tika selects the parser from the content, also with a name that gives no type")
    void routesByContentAndNotByName() throws Exception {
        RarArchiveCollector collector = new RarArchiveCollector();
        ParseContext context = new ParseContext();
        context.set(RarArchiveCollector.class, collector);

        Metadata metadata = new Metadata();
        // The subject of an obfuscated post looks like this. It gives no type and no extension.
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "9401a6eba33647c6ab30f11426506d88");

        try (InputStream in = fixture("rar5-multi.rar")) {
            new AutoDetectParser().parse(in, new BodyContentHandler(), metadata, context);
        }

        assertNotNull(collector.archive(), "Tika must find the RAR from the first bytes");
        assertEquals(4, collector.archive().entries().size());
    }

    @Test
    @DisplayName("a file that is not an archive does not go to the RAR parser")
    void otherContentDoesNotGoToTheRarParser() throws Exception {
        RarArchiveCollector collector = new RarArchiveCollector();
        ParseContext context = new ParseContext();
        context.set(RarArchiveCollector.class, collector);

        Metadata metadata = new Metadata();
        // The name says RAR, but the content is text. The content has more authority.
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "movie.rar");
        byte[] text = "PAYLOAD:not-an-archive:AAAAAAAAAAAAAAAAAAAA\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        new AutoDetectParser().parse(new java.io.ByteArrayInputStream(text),
                new BodyContentHandler(), metadata, context);

        assertNull(collector.archive(), "the RAR parser must not run for text");
        assertTrue(metadata.get(Metadata.CONTENT_TYPE).startsWith("text/"),
                "Tika must give the type of the content: " + metadata.get(Metadata.CONTENT_TYPE));
    }

    @Test
    @DisplayName("AutoDetectParser routes RAR streams here through SPI registration")
    void registeredWithAutoDetectParser() throws Exception {
        RarArchiveCollector collector = new RarArchiveCollector();
        ParseContext context = new ParseContext();
        context.set(RarArchiveCollector.class, collector);
        Metadata metadata = new Metadata();

        try (InputStream in = fixture("rar5-single.rar")) {
            new AutoDetectParser().parse(in, new BodyContentHandler(), metadata, context);
        }

        assertNotNull(collector.archive(),
                "AutoDetectParser should have picked up RarHeaderTikaParser via"
                        + " META-INF/services/org.apache.tika.parser.Parser");
        assertEquals("RAR5", metadata.get(RarMetadata.FORMAT));
        assertEquals(1, collector.archive().entries().size());
    }
}
