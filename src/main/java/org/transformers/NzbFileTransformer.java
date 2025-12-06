package org.transformers;

import org.model.NzbFile;

public interface NzbFileTransformer<T> {
    T transform(NzbFile file) throws Exception;
}
