package org.nzbstreamer.decoder.records;

import org.nzbstreamer.utils.NzbUtils;

public record YencHeader(String filename, long size, int line, String part, String total) {
    public static YencHeader parse(String line) {
        return new YencHeader(
            NzbUtils.extractValue(line, "name"),
            Long.parseLong(NzbUtils.extractValue(line, "size")),
            Integer.parseInt(NzbUtils.extractValue(line, "line")),
            NzbUtils.extractValue(line, "part"),
            NzbUtils.extractValue(line, "total")
        );
    }
}

