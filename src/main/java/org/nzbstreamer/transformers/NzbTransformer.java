package org.nzbstreamer.transformers;

import org.nzbstreamer.model.Nzb;

public interface NzbTransformer<T> {
    T transform(Nzb nzb);
}
