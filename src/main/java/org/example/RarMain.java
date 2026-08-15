package org.example;

import org.nzbstreamer.utils.NzbUtils;
import org.nzbstreamer.streams.VirtualFileInputStream;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.parser.NzbParserFactory;
import org.nzbstreamer.repository.ApplicationContextUtil;
import org.nzbstreamer.service.UsenetDownloadService;

public class RarMain {

    public static void main(String[] args) throws Exception {
        UsenetDownloadService downloadService = ApplicationContextUtil.getBean(UsenetDownloadService.class);

        Nzb nzb = NzbParserFactory.createParser().parse(Main.class.getResourceAsStream("/sample4.nzb"));
        downloadService.populateNzbFileSizes(nzb.getFile(2));
        VirtualFile vf = new VirtualFile(nzb.getFile(2).getSize(), NzbUtils.sanitizeFileName(nzb.getFile(2).getSubject()), nzb.getFile(2));
        VirtualFileInputStream ods = new VirtualFileInputStream(vf);
        int read = 0;
        while ((read = ods.read()) != -1) {
        }
    }
}
