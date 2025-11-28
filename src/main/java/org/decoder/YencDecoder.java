package org.decoder;

import java.io.OutputStream;
import java.io.Reader;

public interface YencDecoder {

    OutputStream decode(Reader reader);
}
