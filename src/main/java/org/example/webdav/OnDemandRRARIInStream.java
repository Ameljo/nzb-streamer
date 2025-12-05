package org.example.webdav;

import net.sf.sevenzipjbinding.IInStream;
import net.sf.sevenzipjbinding.SevenZipException;

import java.io.IOException;

public class OnDemandRRARIInStream implements IInStream {

    private final OnDemandNzbInputStream inputStream;

    public OnDemandRRARIInStream(OnDemandNzbInputStream inputStream) {
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
