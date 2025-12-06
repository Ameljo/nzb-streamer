package org.service;

import org.NzbUtils;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.logging.log4j.Logger;
import org.decoder.MultiPartDecoder;
import org.model.DownloadResult;
import org.model.NzbFile;
import org.model.Segment;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class UsenetAsyncDownloadService {
    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(UsenetAsyncDownloadService.class);
    private final NNTPClient client;
    private final String outputDirectory;

    private static final String SERVER = "YOUR_USENET_SERVER";
    private static final int PORT = 119;
    private static final String USERNAME = "YOUR_USENET_USERNAME";
    private static final String PASSWORD = "YOUR_USENET_PASSWORD";

    public UsenetAsyncDownloadService(NNTPClient client, String outputDirectory) {
        this.client = client;
        this.outputDirectory = outputDirectory;
    }

    public DownloadResult downloadFile(NzbFile file) throws IOException {
        var fileName = sanitizeFileName(file.getSubject());
        var group = file.getGroups().getGroup().getFirst();

        if (!selectNewsgroup(group)) {
            throw new IOException("Failed to select group: " + group);
        }

        var downloadDir = new File(System.getProperty("user.dir"), outputDirectory);
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }

        var outputFile = new File(downloadDir, fileName);
        var segments = file.getSegments().getSegment();
        System.out.println("Downloading (async): " + fileName);

        final Semaphore semaphore = new Semaphore(10); // Limit to 40 concurrent tasks

        List<Future<TempSegment>> futures;

        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<TempSegment>> callables = new ArrayList<>();
            for (var segment: segments){
                callables.add(() -> {
                    semaphore.acquireUninterruptibly();
                    try {
                        return downloadAndDecodeSegment(segment, segments.size(), group);
                    }catch (Exception e){
                        throw new RuntimeException(e);
                    }finally {
                        semaphore.release();
                    }
                });
            }

            futures = executorService.invokeAll(callables);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // Wait for all segments and collect temp files
        Map<Integer, File> tempFiles = new TreeMap<>();
        try {
            for (Future<TempSegment> future : futures) {
                TempSegment temp = future.get();
                tempFiles.put(temp.number, temp.file);
            }
        } catch (Exception e) {
            for (File f : tempFiles.values()) f.delete();
            return DownloadResult.failed(fileName, "Failed to download segment: " + e.getMessage());
        }

        // Concatenate temp files into final output
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (File temp : tempFiles.values()) {
                Files.copy(temp.toPath(), fos);
                temp.delete();
            }
        }

        return DownloadResult.success(fileName, outputFile);
    }

    private TempSegment downloadAndDecodeSegment(Segment segment, int totalSegments, String group) throws IOException {
        var messageId = normalizeMessageId(segment.getValue());
        System.out.printf("  Segment %d/%d: %s (async)%n", segment.getNumber(), totalSegments, messageId);

        // Create a new NNTPClient for this thread
        NNTPClient localClient = new NNTPClient();
        localClient.connect(SERVER, PORT);
        // Authenticate if needed
        localClient.authenticate("YOUR_USENET_USERNAME", "YOUR_USENET_PASSWORD"); // Add your credentials here
        if(!localClient.selectNewsgroup(group)){
            System.err.println(localClient.getReplyString());
            throw new IOException("Failed to select group: " + group);
        }

        Reader reader = localClient.retrieveArticle(messageId);
        if (reader == null) {
            throw new IOException("Article not found: " + messageId +
                    " (Reply: " + localClient.getReplyCode() + " - " + localClient.getReplyString() + ")");
        }

        MultiPartDecoder decoder = new MultiPartDecoder();
        byte[] decoded = decoder.decode(reader);

        // Use a dedicated temp directory in the system temp dir
        File sysTempDir = new File(System.getProperty("java.io.tmpdir"));
        File tempDir = new File(sysTempDir, "nzb-segments");
        if (!tempDir.exists()) tempDir.mkdirs();
        File tempFile = File.createTempFile("segment_" + segment.getNumber() + "_", ".tmp", tempDir);

        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(decoded);
        }

        localClient.disconnect();

        return new TempSegment(segment.getNumber().intValue(), tempFile);
    }

    public byte[] downloadAndDecodeSegment(Segment segment, String group) throws IOException {
        var messageId = NzbUtils.normalizeMessageId(segment.getValue());
//        System.out.printf("  Segment %d/: %s (async)%n", segment.getNumber(), messageId);

 // Add your credentials here
        if(!client.selectNewsgroup(group)){
            System.err.println(client.getReplyString());
            throw new IOException("Failed to select group: " + group);
        }

        Reader reader = client.retrieveArticle(messageId);
        if (reader == null) {
            throw new IOException("Article not found: " + messageId +
                    " (Reply: " + client.getReplyCode() + " - " + client.getReplyString() + ")");
        }

        MultiPartDecoder decoder = new MultiPartDecoder();
        byte[] decoded = decoder.decode(reader);
        log.debug("Decoded segment " + segment.getNumber() + " size: " + decoded.length + " Segment size: " + segment.getBytes());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try  {
            bos.write(decoded);
        } finally {
            bos.close();
        }

        log.debug("Downloaded segment " + segment.getNumber() + " size: " + decoded.length + " Segment size: " + segment.getBytes());

        return bos.toByteArray();
    }


    private boolean selectNewsgroup(String group) throws IOException {
        var selected = client.selectNewsgroup(group);
        log.debug("Selected group " + group + ": " + selected);
        return selected;
    }

    private String normalizeMessageId(String messageId) {
        return messageId.startsWith("<") ? messageId : "<" + messageId + ">";
    }

    private String sanitizeFileName(String fileName) {
        var parts = fileName.split("\"");
        return parts[1].replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    private static class TempSegment {
        final int number;
        final File file;
        TempSegment(int number, File file) {
            this.number = number;
            this.file = file;
        }
    }
}
