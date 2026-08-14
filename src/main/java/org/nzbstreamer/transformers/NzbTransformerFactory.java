package org.nzbstreamer.transformers;

import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.VirtualFile;
import org.springframework.stereotype.Component;

@Component
public class NzbTransformerFactory {

    private final NzbTransformer<String> nzbToStringTransformer = new NzbToStringTransformer();
    private final NzbFileTransformer<VirtualFile> tikaNzbFileTransformer = new TikaNzbFileTransformer();

    public NzbTransformer<String> getTransformer(Nzb nzb) {
        return nzbToStringTransformer;
    }

    /**
     * Gives the transformer for one file of an NZB.
     *
     * <p>There is one transformer for all the files. Tika reads the content of the file and selects
     * the parser. Thus this factory does not examine the name of the file.</p>
     */
    public NzbFileTransformer<VirtualFile> getTransformer(NzbFile file) {
        return tikaNzbFileTransformer;
    }
}
