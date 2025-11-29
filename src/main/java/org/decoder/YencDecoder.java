package org.decoder;

import java.io.OutputStream;
import java.io.Reader;

public interface YencDecoder {

    byte[] decode(Reader reader) throws Exception;
}
