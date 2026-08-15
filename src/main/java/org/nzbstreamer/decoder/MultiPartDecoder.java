package org.nzbstreamer.decoder;

import org.nzbstreamer.decoder.records.YencHeader;
import org.nzbstreamer.decoder.records.YencPartInfo;
import org.nzbstreamer.decoder.records.YencTrailer;
import java.io.*;
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
