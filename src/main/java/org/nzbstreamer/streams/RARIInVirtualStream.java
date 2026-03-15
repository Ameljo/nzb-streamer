package org.nzbstreamer.streams;

import net.sf.sevenzipjbinding.IInStream;
import net.sf.sevenzipjbinding.SevenZipException;

import java.io.IOException;

public class RARIInVirtualStream implements IInStream {

    private final VirtualFileInputStream inputStream;

    public RARIInVirtualStream(VirtualFileInputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    public long seek(long offset, int seekOrigin) throws SevenZipException {
        return this.inputStream.seek(offset, seekOrigin);
    }

    @Override
    public int read(byte[] data) throws SevenZipException {
        try {
            return this.inputStream.read(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
