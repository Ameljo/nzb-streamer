package org.nzbstreamer.transformers;

import org.nzbstreamer.model.NzbFile;

import java.util.List;

public interface NzbFileTransformer<T> {
    List<T> transform(NzbFile file) throws Exception;
}
