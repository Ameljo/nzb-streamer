package org.nzbstreamer.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.client.NzbStreamerClient;
import org.nzbstreamer.exceptions.NzbParseException;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.service.NzbProcessingService;
import org.nzbstreamer.streams.VirtualFileStream;
import org.nzbstreamer.utils.NzbUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/nzb")
public class NzbController {

    private static final Logger log = LogManager.getLogger(NzbController.class);

    @Value("${spring.servlet.multipart.max-file-size}")
    private String maxUploadSize;

    @Autowired
    private NzbProcessingService nzbProcessingService;

    @Autowired
    private NzbStreamerClient client;

    /**
     * Upload and process an NZB file via multipart form
     *
     * @param file The uploaded .nzb file
     * @return Response with processing status and details
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadNzbFile(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Validate file
            if (file.isEmpty()) {
                log.warn("Upload attempt with empty file");
                response.put("success", false);
                response.put("message", "File is empty");
                return ResponseEntity.badRequest().body(response);
            }

            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase().endsWith(".nzb")) {
                log.warn("Upload attempt with invalid file type: {}", filename);
                response.put("success", false);
                response.put("message", "File must be a .nzb file");
                return ResponseEntity.badRequest().body(response);
            }

            log.info("Received NZB file upload request: {}", filename);

            // Process the NZB file
            Nzb nzb = nzbProcessingService.processNzbFile(file.getInputStream(), filename);

            // Build success response
            response.put("success", true);
            response.put("message", "NZB file processed successfully");
            response.put("filename", filename);
            response.put("filesCount", nzb.getFiles().size());

            log.info("Successfully processed NZB file: {} with {} files", filename, nzb.getFiles().size());

            return ResponseEntity.ok(response);

        } catch (NzbParseException e) {
            log.error("Failed to parse NZB file", e);
            response.put("success", false);
            response.put("message", "Failed to parse NZB file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);

        } catch (IOException e) {
            log.error("Failed to read uploaded file", e);
            response.put("success", false);
            response.put("message", "Failed to read uploaded file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);

        } catch (Exception e) {
            log.error("Unexpected error processing NZB file", e);
            response.put("success", false);
            response.put("message", "Unexpected error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Upload and process an NZB file via raw body (supports application/x-nzb)
     *
     * @param nzbContent The raw NZB file content
     * @param filename Optional filename from query parameter
     * @return Response with processing status and details
     */
    @PostMapping(value = "/upload", consumes = {"application/x-nzb", "application/xml", "text/xml"})
    public ResponseEntity<Map<String, Object>> uploadNzbFileRaw(
            @RequestBody byte[] nzbContent,
            @RequestParam(value = "filename", required = false, defaultValue = "uploaded.nzb") String filename) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Validate content
            if (nzbContent == null || nzbContent.length == 0) {
                log.warn("Upload attempt with empty content");
                response.put("success", false);
                response.put("message", "File content is empty");
                return ResponseEntity.badRequest().body(response);
            }

            // Ensure filename has .nzb extension
            if (!filename.toLowerCase().endsWith(".nzb")) {
                filename = filename + ".nzb";
            }

            log.info("Received raw NZB file upload request: {}", filename);

            // Process the NZB file
            java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream(nzbContent);
            Nzb nzb = nzbProcessingService.processNzbFile(inputStream, filename);

            // Build success response
            response.put("success", true);
            response.put("message", "NZB file processed successfully");
            response.put("filename", filename);
            response.put("filesCount", nzb.getFiles().size());

            log.info("Successfully processed raw NZB file: {} with {} files", filename, nzb.getFiles().size());

            return ResponseEntity.ok(response);

        } catch (NzbParseException e) {
            log.error("Failed to parse NZB file", e);
            response.put("success", false);
            response.put("message", "Failed to parse NZB file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);

        } catch (Exception e) {
            log.error("Unexpected error processing NZB file", e);
            response.put("success", false);
            response.put("message", "Unexpected error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Fetches and processes an NZB file from a remote URL.
     *
     * @param body JSON body with a {@code url} key pointing to an NZB file
     * @return Response with processing status and details
     */
    @PostMapping(value = "/upload-url", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> uploadNzbFromUrl(@RequestBody Map<String, String> body) {
        Map<String, Object> response = new HashMap<>();
        String url = body.get("url");

        if (url == null || url.isBlank()) {
            response.put("success", false);
            response.put("message", "URL is required");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            URI uri = URI.create(url.trim());

            log.info("Fetching NZB from URL: {}", url);

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> httpResponse = client.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                log.warn("Remote server returned {} for URL: {}", httpResponse.statusCode(), url);
                response.put("success", false);
                response.put("message", "Remote server returned HTTP " + httpResponse.statusCode());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
            }

            byte[] nzbContent = httpResponse.body();
            if (nzbContent == null || nzbContent.length == 0) {
                response.put("success", false);
                response.put("message", "Remote URL returned empty content");
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response);
            }

            String filename = nzbNameOf(httpResponse, uri);
            Nzb nzb = nzbProcessingService.processNzbFile(
                    new ByteArrayInputStream(nzbContent), filename);

            response.put("success", true);
            response.put("message", "NZB file processed successfully");
            response.put("filename", filename);
            response.put("filesCount", nzb.getFiles().size());
            log.info("Successfully processed NZB from URL: {} ({} files)", url, nzb.getFiles().size());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid URL: {}", url);
            response.put("success", false);
            response.put("message", "Invalid URL: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (NzbParseException e) {
            log.error("Failed to parse NZB from URL: {}", url, e);
            response.put("success", false);
            response.put("message", "Failed to parse NZB file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);

        } catch (Exception e) {
            log.error("Unexpected error processing NZB from URL: {}", url, e);
            response.put("success", false);
            response.put("message", "Unexpected error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Downloads every media file of an NZB to the local {@code downloads} folder, under its real
     * name, decoded through the same {@link VirtualFileStreamFactory#openStream} path that WebDAV
     * and the player use. Nothing is saved to the database. For checking the bytes of a file
     * directly — a hex viewer or a local player, outside HTTP ranges and outside the player's own
     * probing.
     */
    @PostMapping(value = "/download", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> download(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) {
            log.warn("Download attempt with empty file");
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "File is empty");
            return ResponseEntity.badRequest().body(response);
        }
        return download(file.getInputStream());
    }

    @PostMapping(value = "/download", consumes = {"application/x-nzb", "application/xml", "text/xml"})
    public ResponseEntity<Map<String, Object>> downloadRaw(@RequestBody byte[] nzbContent) {
        if (nzbContent == null || nzbContent.length == 0) {
            log.warn("Download attempt with empty content");
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "File content is empty");
            return ResponseEntity.badRequest().body(response);
        }
        return download(new ByteArrayInputStream(nzbContent));
    }

    private ResponseEntity<Map<String, Object>> download(InputStream inputStream) {
        Map<String, Object> response = new HashMap<>();
        try {
            Nzb nzb = client.parse(inputStream);
            // A post of an nfo file gets no size: it holds one small article, and a connection for
            // it costs more than the size that it gives. See NzbFileSizeResolver.
            client.resolveSizes(nzb.getFiles().stream()
                    .filter(f -> !NzbUtils.sanitizeFileName(f.getSubject()).contains(".nfo"))
                    .toList());
            List<VirtualFile> files = client.buildVirtualFiles(nzb);

            File downloadsDir = new File("downloads");
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                throw new IOException("Cannot create the folder " + downloadsDir.getAbsolutePath());
            }

            List<String> saved = new ArrayList<>();
            for (VirtualFile vf : files) {
                File target = new File(downloadsDir, vf.filename());
                log.info("Downloading {} ({} bytes) to {}", vf.filename(), vf.getSize(), target);
                try (VirtualFileStream in = client.openStream(vf);
                     OutputStream out = new FileOutputStream(target)) {
                    in.transferTo(out);
                }
                saved.add(target.getPath());
            }

            response.put("success", true);
            response.put("message", "NZB file downloaded successfully");
            response.put("files", saved);
            response.put("filesCount", files.size());

            log.info("Successfully downloaded {} files of {}", files.size(), nzb.getFiles().size());

            return ResponseEntity.ok(response);

        } catch (NzbParseException e) {
            log.error("Failed to parse NZB file", e);
            response.put("success", false);
            response.put("message", "Failed to parse NZB file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);

        } catch (Exception e) {
            log.error("Unexpected error downloading NZB file", e);
            response.put("success", false);
            response.put("message", "Unexpected error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * The last part of the path of a URL that names the operation of an indexer and not a release.
     * A name of this list gives the same folder to every NZB of that indexer.
     */
    private static final Set<String> GENERIC_URL_SEGMENTS =
            Set.of("download", "downloads", "nzb", "get", "getnzb", "fetch", "api", "file");

    private static final Pattern FILENAME_STAR =
            Pattern.compile("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FILENAME_PLAIN =
            Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    /** The number of characters of a name. The column of the path of a resource holds 255. */
    private static final int NAME_LIMIT = 120;

    /**
     * The name of the NZB that a URL gives.
     *
     * <p>The name becomes the folder of WebDAV of the release, thus two releases need two names.
     * The header {@code Content-Disposition} holds the name that the indexer gives to the file,
     * which is the name of the release; a browser saves the file under that name. The last part of
     * the path of the URL comes after it, because an indexer often ends its links with a word of
     * its API — {@code /download} gives the folder {@code /webdav/download} to every release.</p>
     */
    private static String nzbNameOf(HttpResponse<byte[]> httpResponse, URI uri) {
        String fromHeader = httpResponse.headers().firstValue("Content-Disposition")
                .map(NzbController::filenameOf)
                .filter(name -> !name.isBlank())
                .orElse(null);
        if (fromHeader != null) {
            return asNzbName(fromHeader);
        }

        String path = uri.getPath();
        String segment = path != null && path.contains("/")
                ? path.substring(path.lastIndexOf('/') + 1)
                : "";
        String withoutExtension = segment.toLowerCase().endsWith(".nzb")
                ? segment.substring(0, segment.length() - 4)
                : segment;
        if (!withoutExtension.isBlank()
                && !GENERIC_URL_SEGMENTS.contains(withoutExtension.toLowerCase())) {
            return asNzbName(segment);
        }
        return "downloaded.nzb";
    }

    /** The value of {@code filename} of a header Content-Disposition, or an empty text. */
    private static String filenameOf(String contentDisposition) {
        Matcher encoded = FILENAME_STAR.matcher(contentDisposition);
        if (encoded.find()) {
            return URLDecoder.decode(encoded.group(1).trim(), StandardCharsets.UTF_8);
        }
        Matcher plain = FILENAME_PLAIN.matcher(contentDisposition);
        return plain.find() ? plain.group(1).trim() : "";
    }

    /**
     * Makes a name that is good for a folder of the tree.
     *
     * <p>The name comes from another server, thus this removes the characters of a path: a name
     * that holds a slash or two dots makes a resource outside its folder.</p>
     */
    private static String asNzbName(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|]", "_").replace("..", "_").trim();
        if (clean.toLowerCase().endsWith(".nzb")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        if (clean.length() > NAME_LIMIT) {
            clean = clean.substring(0, NAME_LIMIT);
        }
        return clean.isBlank() ? "downloaded.nzb" : clean + ".nzb";
    }

    /**
     * Answers an upload that passes {@code spring.servlet.multipart.max-file-size}.
     *
     * <p>The container of the servlet throws before it calls the operation of the upload, thus
     * the try of that operation does not see this. Without this handler the caller gets a 500
     * that holds no JSON, and the page shows nothing.</p>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("Upload attempt over the limit of {}", maxUploadSize);
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "File is too large. The limit is " + maxUploadSize + ".");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "NZB Processing Service");
        return ResponseEntity.ok(response);
    }
}

