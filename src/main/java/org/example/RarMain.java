package org.example;

import org.nzbstreamer.utils.NzbUtils;
import org.nzbstreamer.streams.VirtualFileInputStream;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.parser.NzbParserFactory;
import org.nzbstreamer.repository.ApplicationContextUtil;
import org.nzbstreamer.service.NzbFileSizeResolver;
import org.nzbstreamer.streams.VirtualFileStreamFactory;

public class RarMain {

    public static void main(String[] args) throws Exception {
        NzbFileSizeResolver sizeResolver = ApplicationContextUtil.getBean(NzbFileSizeResolver.class);
        VirtualFileStreamFactory streams = ApplicationContextUtil.getBean(VirtualFileStreamFactory.class);

        Nzb nzb = NzbParserFactory.createParser().parse(Main.class.getResourceAsStream("/sample4.nzb"));
        sizeResolver.resolve(nzb.getFile(2));
        VirtualFile vf = new VirtualFile(nzb.getFile(2).getSize(), NzbUtils.sanitizeFileName(nzb.getFile(2).getSubject()), nzb.getFile(2));
        VirtualFileInputStream ods = streams.open(vf);
        int read = 0;
        while ((read = ods.read()) != -1) {
        }
    }
}
