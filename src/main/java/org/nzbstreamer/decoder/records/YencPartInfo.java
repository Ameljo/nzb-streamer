package org.nzbstreamer.decoder.records;

import org.nzbstreamer.utils.NzbUtils;

public record YencPartInfo(long begin, long end, String pcrc32) {
    public static YencPartInfo parse(String line) {
        return new YencPartInfo(
            Long.parseLong(NzbUtils.extractValue(line, "begin")),
            Long.parseLong(NzbUtils.extractValue(line, "end")),
            NzbUtils.extractValue(line, "pcrc32")
        );
    }
}

