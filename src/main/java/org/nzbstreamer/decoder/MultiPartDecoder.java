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
