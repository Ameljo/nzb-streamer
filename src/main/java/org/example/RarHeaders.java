package org.example;


import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import org.NzbUtils;
import org.webdav.OnDemandNzbInputStream;
import org.webdav.VirtualFile;
import org.exceptions.NzbParseException;
import org.model.Nzb;
import org.parser.NzbParserFactory;

import java.io.*;

public class RarHeaders {
    public static void main(String[] args) throws IOException, RarException, NzbParseException {
        File rarFile = new File("downloads/Lowcash-Future-SINGLE-WEB-2025-MARiBOR.rar");
        Nzb nzb = NzbParserFactory.createParser().parse(Main.class.getResourceAsStream("/sample4.nzb"));
        VirtualFile vf = new VirtualFile(nzb.getFile(2).getTotalBytes(), NzbUtils.sanitizeFileName(nzb.getFile(2).getSubject()), nzb.getFile(2));
        InputStream is = new FileInputStream(rarFile);
        OnDemandNzbInputStream ods = new OnDemandNzbInputStream(vf);
            Archive archive = new Archive(ods);
            System.out.println("Created Archive object");
            archive.getMainHeader().print();
    }
}
