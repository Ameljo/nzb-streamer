package org.nzbstreamer.decoder;

import org.nzbstreamer.decoder.records.YencHeader;
import org.nzbstreamer.decoder.records.YencPartInfo;

import java.io.Reader;

public interface YencDecoder {

    byte[] decode(Reader reader) throws Exception;

    YencHeader parseYencHeader(Reader reader) throws Exception;

    YencPartInfo parseYencPartInfo(Reader reader) throws Exception;
}
