package org.example;

import org.NzbUtils;
import org.apache.commons.net.nntp.NNTPClient;
import org.webdav.OnDemandNzbInputStream;
import org.webdav.VirtualFile;
import org.model.Nzb;
import org.parser.NzbParserFactory;
import org.service.UsenetDownloadService;

public class RarMain {

    private static final String SERVER = "YOUR_USENET_SERVER";
    private static final int PORT = 119;
    private static final String USERNAME = "YOUR_USENET_USERNAME";
    private static final String PASSWORD = "YOUR_USENET_PASSWORD";


    public static void main(String[] args) throws Exception {
        NNTPClient client = new NNTPClient();
        client.connect(SERVER, PORT);
        client.authenticate(USERNAME, PASSWORD);
        UsenetDownloadService downloadService = new UsenetDownloadService(client, "downloads");

        Nzb nzb = NzbParserFactory.createParser().parse(Main.class.getResourceAsStream("/sample4.nzb"));
        downloadService.populateNzbFileSizes(nzb.getFile(2));
        VirtualFile vf = new VirtualFile(nzb.getFile(2).getSize(), NzbUtils.sanitizeFileName(nzb.getFile(2).getSubject()), nzb.getFile(2));
        OnDemandNzbInputStream ods = new OnDemandNzbInputStream(vf);
        int read = 0;
       while ((read =  ods.read()) != -1){
       }
       client.logout();
       client.disconnect();
    }
}
