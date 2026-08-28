package org.nzbstreamer.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.client.NzbStreamerClient;
import org.nzbstreamer.exceptions.NzbParseException;
import org.nzbstreamer.exceptions.UsenetException;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.utils.NzbUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Reads an uploaded NZB and puts its files in the library.
 *
 * <p>The work has four steps, and each one has its own operation: read the XML, give the posts
 * their true sizes, make the files of the posts, save them. The three first steps make no write
 * in the database, and the last one makes no connection to the news server.</p>
 */
@Service
public class NzbProcessingService {

    private static final Logger log = LogManager.getLogger(NzbProcessingService.class);

    @Autowired
    private NzbStreamerClient client;

    @Autowired
    private VirtualFileStore store;

    /**
     * Reads an NZB and saves the files that it holds.
     *
     * <p>This operation opens no transaction: the steps that read the news server take minutes on
     * a large NZB, and a transaction of that length holds a connection of the database for a
     * conversation with a news server. {@link VirtualFileStore#save} opens the transaction, and it
     * only writes.</p>
     *
     * @throws NzbParseException if the XML of the NZB is not readable. An error of the news server
     *         or of the database goes to the caller as it is.
     */
    public Nzb processNzbFile(InputStream inputStream, String filename)
            throws NzbParseException, IOException, InterruptedException, UsenetException {
        long startedAt = System.nanoTime();
        log.info("Processing NZB file: {}", filename);

        Nzb nzb = client.parse(inputStream);
        log.info("Successfully parsed NZB file: {} with {} files", filename, nzb.getFiles().size());

        long sizesStart = System.nanoTime();
        resolveSizes(nzb);
        log.info("Resolved sizes for {} files in {} ms", nzb.getFiles().size(),
                (System.nanoTime() - sizesStart) / 1_000_000);

        long transformStart = System.nanoTime();
        List<VirtualFile> virtualFiles = transform(nzb);
        log.info("Transformed NZB into {} files in {} ms", virtualFiles.size(),
                (System.nanoTime() - transformStart) / 1_000_000);

        long saveStart = System.nanoTime();
        store.save(virtualFiles, folderNameOf(filename));
        log.info("Saved files in {} ms", (System.nanoTime() - saveStart) / 1_000_000);

        log.info("NZB file processing completed successfully: {} in {} ms", filename,
                (System.nanoTime() - startedAt) / 1_000_000);
        return nzb;
    }

    /**
     * Gives the posts their true sizes.
     *
     * <p>The parser gives the sizes of the articles, which hold the yEnc data. The resolver reads
     * the first article of each post and gives the sizes of the decoded segments, which a seek
     * needs.</p>
     *
     * <p>A post of a repair or metadata file (PAR2, SFV, NFO, ...) gets none. It never becomes
     * media, and a connection for it costs more than the size that it gives.</p>
     */
    private void resolveSizes(Nzb nzb) throws IOException, InterruptedException, UsenetException {
        client.resolveSizes(nzb.getFiles().stream()
                .filter(file -> !NzbUtils.isRepairOrMetadataFile(file.getSubject()))
                .toList());
    }

    /**
     * Makes the files of the posts.
     *
     * <p>One transformer reads all the NZB, because a file of an archive can continue from one
     * volume to the next volume.</p>
     */
    private List<VirtualFile> transform(Nzb nzb) {
        List<VirtualFile> found = client.buildVirtualFiles(nzb);
        return found == null ? List.of() : found;
    }

    /** The name of the folder of WebDAV for an NZB: its file name without the extension. */
    private static String folderNameOf(String filename) {
        return filename.endsWith(".nzb")
                ? filename.substring(0, filename.length() - 4)
                : filename;
    }
}
