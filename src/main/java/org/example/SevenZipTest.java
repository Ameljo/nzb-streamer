package org.example;

import net.sf.sevenzipjbinding.*;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import net.sf.sevenzipjbinding.simple.ISimpleInArchive;
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem;
import org.nzbstreamer.utils.NzbUtils;
import org.apache.commons.net.nntp.NNTPClient;
import org.nzbstreamer.streams.VirtualFileInputStream;
import org.nzbstreamer.streams.RARIInVirtualStream;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.parser.NzbParserFactory;
import org.nzbstreamer.service.UsenetDownloadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Arrays;

public class SevenZipTest {
    private static final String SERVER = "YOUR_USENET_SERVER";
    private static final int PORT = 119;
    private static final String USERNAME = "YOUR_USENET_USERNAME";
    private static final String PASSWORD = "YOUR_USENET_PASSWORD";
    private static final Logger log = LoggerFactory.getLogger(SevenZipTest.class);

    public static void main(String[] args) throws Exception {
        String archiveFilename = "downloads/test.rar";

        RandomAccessFile randomAccessFile = null;
        IInArchive inArchive = null;
        RandomAccessFile rarFile = new RandomAccessFile(archiveFilename, "r");
        try {
            inArchive = SevenZip.openInArchive(null, new RandomAccessFileInStream(rarFile));
            inArchive.getArchiveProperty(PropID.HEADERS_SIZE);
            for(int i = 0; i < inArchive.getNumberOfArchiveProperties(); i++) {
                PropID propID = inArchive.getArchivePropertyInfo(i).propID;
                Object propValue = inArchive.getArchiveProperty(propID);
                System.out.println("Archive property: " + propID + " = " + propValue);
            }
            for (int itemIndex = 0; itemIndex < inArchive.getNumberOfItems(); itemIndex++) {
                System.out.println("Item #" + itemIndex + ":");
                for (int propIndex = 0; propIndex < inArchive.getNumberOfProperties(); propIndex++) {
                    PropID propID = inArchive.getPropertyInfo(propIndex).propID;
                    Object propValue = inArchive.getProperty(itemIndex, propID);
                    System.out.println("  Property: " + propID + " = " + propValue);
                }
            }


            // Getting simple interface of the archive inArchive
            ISimpleInArchive simpleInArchive = inArchive.getSimpleInterface();

            System.out.println("   Size   | Compr.Sz. | Filename");
            System.out.println("----------+-----------+---------");

            for (ISimpleInArchiveItem item : simpleInArchive.getArchiveItems()) {
                System.out.println("Comment: " + item.getComment());
                System.out.println("Position: " + item.getPosition());
                final int[] hash = new int[] { 0 };
                if (!item.isFolder()) {
                    ExtractOperationResult result;

                    final long[] sizeArray = new long[1];
                    File extractedFile = new File("downloads/" + item.getPath());

//                    OutputStream os = new FileOutputStream(extractedFile);
//                    result = item.extractSlow(new ISequentialOutStream() {
//                        public int write(byte[] data) throws SevenZipException {
//                            try {
//                                os.write(data);
//                            } catch (IOException e) {
//                                throw new SevenZipException("Error writing extracted data", e);
//                            }
//                            hash[0] ^= Arrays.hashCode(data); // Consume data
//                            sizeArray[0] += data.length;
//                            return data.length; // Return amount of consumed data
//                        }
//                    });

//                    if (result == ExtractOperationResult.OK) {
//                        System.out.println(String.format("%9X | %10s | %s",
//                                hash[0], sizeArray[0], item.getPath()));
//                        try {
//                            os.close();
//                        } catch (IOException e) {
//                            System.err.println("Error closing output stream: " + e);
//                        }
//                    } else {
//                        System.err.println("Error extracting item: " + result);
//                    }
                }
            }
        } catch (SevenZipException ex) {
            throw new RuntimeException(ex);
        } finally {
            if (inArchive != null) {
                try {
                    inArchive.close();
                } catch (SevenZipException e) {
                    System.err.println("Error closing archive: " + e);
                }
            }
            try {
                randomAccessFile.close();
            } catch (IOException e) {
                System.err.println("Error closing file: " + e);
            }
        }
    }
}
