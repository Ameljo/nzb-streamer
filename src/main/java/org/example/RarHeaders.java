package org.example;


import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import org.nzbstreamer.utils.NzbUtils;
import org.nzbstreamer.streams.VirtualFileInputStream;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.exceptions.NzbParseException;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.parser.NzbParserFactory;

import java.io.*;

public class RarHeaders {
    public static void main(String[] args) throws IOException, RarException, NzbParseException {
        File rarFile = new File("downloads/test.rar");
        Nzb nzb = NzbParserFactory.createParser().parse(Main.class.getResourceAsStream("/sample4.nzb"));
        VirtualFile vf = new VirtualFile(nzb.getFile(2).getTotalBytes(), NzbUtils.sanitizeFileName(nzb.getFile(2).getSubject()), nzb.getFile(2));
        InputStream is = new FileInputStream(rarFile);
        VirtualFileInputStream ods = new VirtualFileInputStream(vf);
            Archive archive = new Archive(ods);
            System.out.println("Created Archive object");
            archive.getMainHeader().print();
    }
}
