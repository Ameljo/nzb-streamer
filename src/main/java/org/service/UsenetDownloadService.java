package org.service;

import org.apache.commons.net.nntp.NNTPClient;
import org.decoder.MultiPartDecoder;
import org.model.DownloadResult;
import org.model.Nzb;

import java.io.*;
import java.util.List;

public class UsenetDownloadService {
    private final NNTPClient client;
    private final String outputDirectory;

    public UsenetDownloadService(NNTPClient client, String outputDirectory) {
        this.client = client;
        this.outputDirectory = outputDirectory;
    }

    public DownloadResult downloadFile(Nzb.File file) throws IOException {
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

        System.out.println("Downloading: " + fileName);

        // Download all segments
        try(FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (var segment : segments) {
                try {
                    var segmentData = downloadSegment(segment, segments.size());
                    fos.write(segmentData);
                } catch (IOException e) {
                    return DownloadResult.failed(fileName, "Failed to download segment: " + e.getMessage());
                }
            }
        }

        return DownloadResult.success(fileName, outputFile);
    }

    private byte[] downloadSegment(Nzb.File.Segments.Segment segment, int totalSegments) throws IOException {
        var messageId = normalizeMessageId(segment.getValue());

        System.out.printf("  Segment %d/%d: %s%n",
                segment.getNumber(), totalSegments, messageId);

        var reader = client.retrieveArticle(messageId);
        if (reader == null) {
            throw new IOException("Article not found: " + messageId +
                    " (Reply: " + client.getReplyCode() + " - " + client.getReplyString() + ")");
        }
        MultiPartDecoder decoder = new MultiPartDecoder();
        return decoder.decode(reader);
    }

    private String extractYencData(Reader reader) throws IOException {
        var content = new StringBuilder();
        try (var br = new BufferedReader(reader)) {
            String line;
            var inYencData = false;

            while ((line = br.readLine()) != null) {
                if (line.startsWith("=ybegin") || line.startsWith("=ypart")) {
                    inYencData = true;
                }

                if (inYencData) {
                    content.append(line).append("\r\n");
                }

                if (line.startsWith("=yend")) {
                    break;
                }
            }
        }
        return content.toString();
    }

    private boolean selectNewsgroup(String group) throws IOException {
        var selected = client.selectNewsgroup(group);
        System.out.println("Selected group " + group + ": " + selected);
        return selected;
    }

    private String normalizeMessageId(String messageId) {
        return messageId.startsWith("<") ? messageId : "<" + messageId + ">";
    }

    private String sanitizeFileName(String fileName) {
        var parts = fileName.split("\"");
        return parts[1].replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}