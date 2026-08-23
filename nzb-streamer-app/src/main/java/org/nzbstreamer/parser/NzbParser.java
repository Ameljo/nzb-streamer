package org.nzbstreamer.parser;

import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.exceptions.NzbParseException;

import java.io.InputStream;

public interface NzbParser {
    Nzb parse(InputStream input) throws NzbParseException;
}
