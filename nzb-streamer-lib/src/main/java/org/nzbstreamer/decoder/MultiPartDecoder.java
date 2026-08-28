package org.nzbstreamer.decoder;

import org.nzbstreamer.decoder.records.YencHeader;
import org.nzbstreamer.decoder.records.YencPartInfo;
import org.nzbstreamer.decoder.records.YencTrailer;
import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.zip.CRC32;

/**
 * Multi-part yEnc decoder implementation.
 * Handles yEnc files that are split into multiple parts (=ypart).
 */
public class MultiPartDecoder extends AbstractYencDecoder implements YencDecoder {
    @Override
    public byte[] decode(Reader reader) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            var crc = new CRC32();
            var inYencData = false;
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                switch (line) {
                    case String s when s.startsWith("=ybegin") -> {
                        inYencData = true;
                    }
                    case String s when s.startsWith("=ypart") -> {}
                    case String s when s.startsWith("=yend") -> {
                        var trailer = YencTrailer.parse(s);
//                        validatePart(crc.getValue(), trailer); //TODO fix validation failing for nfo files
                        return output.toByteArray();
                    }
                    default -> {
                        if (inYencData) {
                            decodeLine(line, crc, output);
                        }
                    }
                }
            }
        }
        return output.toByteArray();
    }

    /**
     * Decodes the first bytes of the part, and stops when it holds {@code maxBytes} or more.
     *
     * <p>This operation does not close the reader when it stops at {@code maxBytes}: a close
     * operation reads the rest of the article. The caller thus decides what to do with the bytes
     * that come after, and it can give the connection to another thread.</p>
     *
     * @return the bytes, and a length of {@code maxBytes} or more when the part continues
     */
    public byte[] decodePrefix(Reader reader, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BufferedReader bufferedReader = new BufferedReader(reader);
        var crc = new CRC32();
        var inYencData = false;
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            if (line.startsWith("=ybegin")) {
                inYencData = true;
            } else if (line.startsWith("=yend")) {
                bufferedReader.close();
                return output.toByteArray();
            } else if (inYencData && !line.startsWith("=ypart")) {
                decodeLine(line, crc, output);
                if (output.size() >= maxBytes) {
                    return output.toByteArray();
                }
            }
        }
        bufferedReader.close();
        return output.toByteArray();
    }

    /**
     * Decodes the reader and puts chunks of about {@code bufferSize} bytes on the queue as soon as
     * they are ready, instead of giving one array at the end.
     *
     * <p>The first {@code skip} decoded bytes of the part are discarded, and at most {@code trim}
     * bytes after that reach the queue: the archive can hold bytes before and after the position
     * that the caller wants — {@code skip} is {@code Location.byteInSegment()} and {@code trim} is
     * {@code Location.bytesLeftInSegment()}. The CRC still covers every byte of the part, since the
     * checksum is over all of it.</p>
     *
     * <p>The last chunk it puts can hold fewer than {@code bufferSize} bytes: the part, or the
     * {@code trim} window, can end before one fills.</p>
     *
     * @return the whole part, not just the {@code [skip, skip+trim)} window given to the queue --
     *         so a caller can cache the complete segment, reusable for any other window of it
     */
    public byte[] decode(Reader reader, BlockingQueue<byte[]> bufferQueue, int bufferSize, int skip,
                      int trim) throws IOException, InterruptedException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ByteArrayOutputStream full = new ByteArrayOutputStream();
        OutputStream output = new WindowOutputStream(buffer, full, skip, trim);
        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            var crc = new CRC32();
            var inYencData = false;
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                switch (line) {
                    case String s when s.startsWith("=ybegin") -> inYencData = true;
                    case String s when s.startsWith("=ypart") -> {}
                    case String s when s.startsWith("=yend") -> {
                        var trailer = YencTrailer.parse(s);
//                        validatePart(crc.getValue(), trailer); //TODO fix validation failing for nfo files
                        if (buffer.size() > 0) {
                            bufferQueue.put(buffer.toByteArray());
                        }
                        return full.toByteArray();
                    }
                    default -> {
                        if (inYencData) {
                            decodeLine(line, crc, output);
                            if (buffer.size() >= bufferSize) {
                                bufferQueue.put(buffer.toByteArray());
                                buffer.reset();
                            }
                        }
                    }
                }
            }
        }
        if (buffer.size() > 0) {
            bufferQueue.put(buffer.toByteArray());
        }
        return full.toByteArray();
    }

    /**
     * Writes every byte to {@code full}, and only the {@code [skip, skip+trim)} window of them to
     * {@code delegate}.
     *
     * <p>{@code full} never resets, unlike {@code delegate}: it is the whole part, for a caller
     * that wants the complete segment as one array once the decode is done -- to cache it, for
     * instance -- alongside the windowed chunks that {@code delegate} already sent to the queue as
     * they were ready.</p>
     */
    private static final class WindowOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final OutputStream full;
        private int skip;
        private int trim;

        WindowOutputStream(OutputStream delegate, OutputStream full, int skip, int trim) {
            this.delegate = delegate;
            this.full = full;
            this.skip = skip;
            this.trim = trim;
        }

        @Override
        public void write(int b) throws IOException {
            full.write(b);
            if (skip > 0) {
                skip--;
                return;
            }
            if (trim <= 0) {
                return;
            }
            trim--;
            delegate.write(b);
        }
    }

    @Override
    public YencPartInfo parseYencPartInfo(Reader reader) throws Exception {
        try (BufferedReader bufferedReader = new BufferedReader(reader)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if(line.startsWith("=ypart")) {
                    return YencPartInfo.parse(line);
                }
            }
        }
        return null;
    }
}
