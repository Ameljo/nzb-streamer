package org.parser;

import org.model.Nzb;
import org.exceptions.NzbParseException;

import java.io.InputStream;

public interface NzbParser {
    Nzb parse(InputStream input) throws NzbParseException;
}
