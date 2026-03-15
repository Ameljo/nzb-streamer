package org.nzbstreamer.controller;

import org.apache.commons.net.nntp.NNTPClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.Tika;
import org.example.NNTPClientFactory;
import org.nzbstreamer.exceptions.NzbParseException;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.parser.NzbParser;
import org.nzbstreamer.parser.NzbParserFactory;
import org.nzbstreamer.service.NzbProcessingService;
import org.nzbstreamer.service.UsenetDownloadService;
import org.nzbstreamer.transformers.NzbFileToVirtualFileTransformer;
import org.nzbstreamer.transformers.NzbFileTransformer;
import org.nzbstreamer.transformers.NzbRarFileToVirtualFileTransformer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/nzb")
public class NzbController {

    private static final Logger log = LogManager.getLogger(NzbController.class);

    @Autowired
    private NzbProcessingService nzbProcessingService;

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

    @PostMapping(value = "/test", consumes = {"application/x-nzb", "application/xml", "text/xml"})
    public ResponseEntity<Map<String, Object>> test(
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
            Nzb nzb = nzbProcessingService.processNzbFileWithoutSaving(inputStream, filename);

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

    @PostMapping(value = "/download", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> download(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Validate content
            if (file.isEmpty()) {
                log.warn("Upload attempt with empty file");
                response.put("success", false);
                response.put("message", "File is empty");
                return ResponseEntity.badRequest().body(response);
            }

            String filename = file.getOriginalFilename();

            log.info("Received raw NZB file upload request: {}", filename);

            // Process the NZB file
            InputStream inputStream = file.getInputStream();
            NzbParser parser = NzbParserFactory.createParser();
            Nzb nzb = parser.parse(inputStream);


            UsenetDownloadService usenetDownloadService = new UsenetDownloadService(NNTPClientFactory.getAuthenticatedClient());
            int i=1;
            for (NzbFile nzbFile : nzb.getFiles()) {
                if (i > 1)
                    filename = "sample.part" + i + ".rar";
                else
                    filename = "sample.part1.rar";
                i++;
                File downloadedNzbFile = new File("downloads/" + filename );
                try(OutputStream output = new FileOutputStream(downloadedNzbFile)) {
                    usenetDownloadService.downloadFile(nzbFile, output);
                }
            }
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

