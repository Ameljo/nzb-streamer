package org.example;

import net.sf.sevenzipjbinding.*;
import net.sf.sevenzipjbinding.simple.ISimpleInArchive;
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem;
import org.NzbUtils;
import org.apache.commons.net.nntp.NNTPClient;
import org.webdav.OnDemandNzbInputStream;
import org.webdav.OnDemandRRARIInStream;
import org.webdav.VirtualFile;
import org.model.Nzb;
import org.parser.NzbParserFactory;
import org.service.UsenetDownloadService;

import java.io.*;
import java.util.Arrays;

public class SevenZipTest {
    private static final String SERVER = "YOUR_USENET_SERVER";
    private static final int PORT = 119;
    private static final String USERNAME = "YOUR_USENET_USERNAME";
    private static final String PASSWORD = "YOUR_USENET_PASSWORD";

    public static void main(String[] args) throws Exception {
        String archiveFilename = "downloads/Lowcash-Future-SINGLE-WEB-2025-MARiBOR.rar";

        RandomAccessFile randomAccessFile = null;
        IInArchive inArchive = null;

        NNTPClient client = new NNTPClient();
        client.connect(SERVER, PORT);
        client.authenticate(USERNAME, PASSWORD);
        UsenetDownloadService downloadService = new UsenetDownloadService(client, "downloads");

        Nzb nzb = NzbParserFactory.createParser().parse(Main.class.getResourceAsStream("/sample4.nzb"));
        downloadService.populateNzbFileSizes(nzb.getFile(2));
        VirtualFile vf = new VirtualFile(nzb.getFile(2).getSize(), NzbUtils.sanitizeFileName(nzb.getFile(2).getSubject()), nzb.getFile(2));
        OnDemandNzbInputStream ods = new OnDemandNzbInputStream(vf);

        OnDemandRRARIInStream rrarIInStream = new OnDemandRRARIInStream(ods);

        try {
            inArchive = SevenZip.openInArchive(null, rrarIInStream);

            // Getting simple interface of the archive inArchive
            ISimpleInArchive simpleInArchive = inArchive.getSimpleInterface();

            System.out.println("   Size   | Compr.Sz. | Filename");
            System.out.println("----------+-----------+---------");

            for (ISimpleInArchiveItem item : simpleInArchive.getArchiveItems()) {
                final int[] hash = new int[] { 0 };
                if (!item.isFolder()) {
                    ExtractOperationResult result;

                    final long[] sizeArray = new long[1];
                    File extractedFile = new File("downloads/" + item.getPath());

                    OutputStream os = new FileOutputStream(extractedFile);
                    result = item.extractSlow(new ISequentialOutStream() {
                        public int write(byte[] data) throws SevenZipException {
                            try {
                                os.write(data);
                            } catch (IOException e) {
                                throw new SevenZipException("Error writing extracted data", e);
                            }
                            hash[0] ^= Arrays.hashCode(data); // Consume data
                            sizeArray[0] += data.length;
                            return data.length; // Return amount of consumed data
                        }
                    });

                    if (result == ExtractOperationResult.OK) {
                        System.out.println(String.format("%9X | %10s | %s",
                                hash[0], sizeArray[0], item.getPath()));
                        try {
                            os.close();
                        } catch (IOException e) {
                            System.err.println("Error closing output stream: " + e);
                        }
                    } else {
                        System.err.println("Error extracting item: " + result);
                    }
                }
            }
        } catch (SevenZipException ex) {
            throw new RuntimeException(ex);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } finally {
            if (inArchive != null) {
                try {
                    inArchive.close();
                } catch (SevenZipException e) {
                    System.err.println("Error closing archive: " + e);
                }
            }
            try {
                rrarIInStream.close();
            } catch (IOException e) {
                System.err.println("Error closing file: " + e);
            }
        }
    }
}
