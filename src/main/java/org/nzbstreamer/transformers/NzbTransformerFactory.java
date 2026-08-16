package org.nzbstreamer.transformers;

import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.streams.VirtualFileStreamFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NzbTransformerFactory {

    private final NzbTransformer<List<VirtualFile>> tikaNzbFileTransformer;

    public NzbTransformerFactory(VirtualFileStreamFactory streams) {
        this.tikaNzbFileTransformer = new TikaNzbFileTransformer(streams);
    }

    /**
     * Gives the transformer for an NZB.
     *
     * <p>There is one transformer for all the files. Tika reads the content of each post and
     * selects the parser. Thus this factory does not examine the name of a post.</p>
     */
    public NzbTransformer<List<VirtualFile>> getTransformer(Nzb nzb) {
        return tikaNzbFileTransformer;
    }
}
