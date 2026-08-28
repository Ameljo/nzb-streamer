package org.nzbstreamer.decoder;

import org.nzbstreamer.decoder.records.YencHeader;
import org.nzbstreamer.decoder.records.YencPartInfo;
import org.nzbstreamer.decoder.records.YencTrailer;

import java.io.*;
import java.util.zip.CRC32;

/**
 * Single-part yEnc decoder implementation.
 * Handles yEnc files that are not split into multiple parts.
 */
public class SingePartDecoder extends AbstractYencDecoder implements YencDecoder {
    @Override
    public YencPartInfo parseYencPartInfo(Reader reader) throws Exception {
        throw new UnsupportedOperationException("Single-part decoder does not support part info parsing.");
    }

    @Override
    public byte[] decode(Reader reader) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var bufferedReader = new BufferedReader(reader)) {
            var crc = new CRC32();
            var inYencData = false;
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                switch (line) {
                    case String s when s.startsWith("=ybegin") -> {
                        inYencData = true;
                    }
                    case String s when s.startsWith("=yend") -> {
                        var trailer = YencTrailer.parse(s);
                        validatePart(crc.getValue(), trailer);
                        return output.toByteArray();
                    }
                    default -> {
                        if (inYencData) {
                            decodeLine(line, crc, output);
                        }
                    }
                }
            }
            return output.toByteArray();
        }
    }

}
