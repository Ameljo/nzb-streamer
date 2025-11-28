package org.parser;

public class NzbParserFactory {
    public static NzbParser createParser() {
        return new JaxbNzbParser();
    }
}
