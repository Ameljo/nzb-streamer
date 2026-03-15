package org.nzbstreamer.parser;

public class NzbParserFactory {
    public static NzbParser createParser() {
        return new JaxbNzbParser();
    }
}
