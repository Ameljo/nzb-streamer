package org.transformers;

import org.model.Nzb;

public interface NzbTransformer<T> {
    T transform(Nzb nzb);
}
