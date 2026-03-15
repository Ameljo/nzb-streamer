package org.nzbstreamer.decoder.records;

import org.nzbstreamer.utils.NzbUtils;

import java.util.Objects;

public record YencTrailer(long size, String part, String pcrc32, String crc32) {
    public static YencTrailer parse(String line) {
        return new YencTrailer(
            Long.parseLong(Objects.requireNonNull(NzbUtils.extractValue(line, "size"))),
            NzbUtils.extractValue(line, "part"),
            NzbUtils.extractValue(line, "pcrc32"),
            NzbUtils.extractValue(line, "crc32")
        );
    }
}

