package org.example;

import org.apache.commons.net.nntp.ArticleInfo;
import org.apache.commons.net.nntp.NNTPClient;
import org.decoder.MultiPartDecoder;
import org.model.Nzb;
import org.parser.NzbParserFactory;
import org.service.UsenetDownloadService;
import org.transformers.NzbToStringTransformer;
import org.transformers.NzbTransformer;

import java.io.*;
import java.util.List;

public class Main {
    private static final String SERVER = "YOUR_USENET_SERVER";
    private static final int PORT = 119;
    private static final String USERNAME = "YOUR_USENET_USERNAME";
    private static final String PASSWORD = "YOUR_USENET_PASSWORD";


    public static void main(String[] args) {
        try {
            Nzb nzb = NzbParserFactory.createParser().parse(Main.class.getResourceAsStream("/sample4.nzb"));
            NzbTransformer<String> transformer = new NzbToStringTransformer();
            NNTPClient client = new NNTPClient();
            client.connect(SERVER, PORT);
            System.out.println(client.authenticate(USERNAME, PASSWORD));
            UsenetDownloadService service = new UsenetDownloadService(client, "downloads");
            for( Nzb.File file : nzb.getFile()) {
                downloadFile(file, "downloads", client);
                service.downloadFile(file);
            }
            client.disconnect();
        } catch (Exception e) {
            System.err.println("Error parsing NZB: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void downloadFile(Nzb.File file, String outputDirectory, NNTPClient client) throws IOException {
        String fileName = sanitizeFileName(file.getSubject());
        boolean hasMissing = false;

        String group = file.getGroups().getGroup().getFirst();
        boolean groupSelected = client.selectNewsgroup(group);
        System.out.println("Selected group " + group + ": " + groupSelected);

        if (!groupSelected) {
            throw new IOException("Failed to select group: " + group);
        }


        java.io.File downloadDir = new java.io.File(System.getProperty("user.dir"), outputDirectory);
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        java.io.File outputFile = new java.io.File(downloadDir, fileName);

        System.out.println("Downloading: " + fileName);

            List<Nzb.File.Segments.Segment> segments = file.getSegments().getSegment();

        // Download segments in order
        for (Nzb.File.Segments.Segment segment : segments) {
            String messageId = segment.getValue();
            System.out.printf("  Segment %d/%d: %s%n",
                    segment.getNumber(), segments.size(), messageId);

            // Ensure message ID has angle brackets
            if (!messageId.startsWith("<")) {
                messageId = "<" + messageId + ">";
            }
            if (!client.selectArticle(messageId)) {
                // Check the last reply code for debugging
                System.err.println("Failed to retrieve article. Reply code: " + client.getReplyCode());
                System.err.println("Reply string: " + client.getReplyString());
                hasMissing = true;
                break;
            }

            try (FileOutputStream fos = new FileOutputStream(outputFile, true)) {
//                byte[] data = downloadArticle(messageId, client);
                Reader reader = client.retrieveArticle(messageId);
                if (reader == null) {
                    // Check the last reply code for debugging
                    System.err.println("Failed to retrieve article. Reply code: " + client.getReplyCode());
                    System.err.println("Reply string: " + client.getReplyString());
                    throw new IOException("Article not found: " + messageId);
                }
                MultiPartDecoder decoder = new MultiPartDecoder();
                byte[] data = decoder.decode(reader);
                fos.write(data);
            }
        }

        if (hasMissing) {
            System.out.println("Download incomplete, missing segments for: " + fileName);
        } else {
            System.out.println("Download complete: " + fileName);
        }
    }

    private static String sanitizeFileName(String fileName) {
        String[] parts = fileName.split("\"");
        return parts[1].replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    public static byte[] downloadArticle(String messageId, NNTPClient client) throws IOException {
        // Try retrieving with ARTICLE command first (gets headers + body)
        Reader reader = client.retrieveArticle(messageId);
        if (reader == null) {
            // Check the last reply code for debugging
            System.err.println("Failed to retrieve article. Reply code: " + client.getReplyCode());
            System.err.println("Reply string: " + client.getReplyString());
            throw new IOException("Article not found: " + messageId);
        }

       return decodeYenc(reader);
    }

    private static byte[] decodeYenc(Reader reader) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            boolean inYenc = false;

            while ((line = br.readLine()) != null) {
                if (line.startsWith("=ybegin")) {
                    inYenc = true;
                    continue;
                }
                if (line.startsWith("=yend")) {
                    break;
                }
                if (inYenc && !line.startsWith("=ypart")) {
                    // Decode yEnc line
                    byte[] decoded = decodeYencLine(line);
                    output.write(decoded);
                }
            }
        }

        return output.toByteArray();
    }

    private static byte[] decodeYencLine(String line) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '=') {
                // Escaped character
                i++;
                if (i < line.length()) {
                    c = line.charAt(i);
                    output.write((byte)((c - 64 - 42) & 0xFF));
                }
            } else {
                output.write((byte)((c - 42) & 0xFF));
            }
        }

        return output.toByteArray();
    }
}
