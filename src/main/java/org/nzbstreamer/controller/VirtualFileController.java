package org.nzbstreamer.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.VirtualResource;
import org.nzbstreamer.repository.VirtualFileRepository;
import org.nzbstreamer.repository.VirtualResourceRepository;
import org.nzbstreamer.streams.VirtualFileStream;
import org.nzbstreamer.streams.VirtualFileStreamFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

@RestController
@RequestMapping("/api/files")
public class VirtualFileController {

    private static final Logger log = LogManager.getLogger(VirtualFileController.class);

    @Autowired
    private VirtualFileRepository virtualFileRepository;

    @Autowired
    private VirtualResourceRepository virtualResourceRepository;

    @Autowired
    private VirtualFileStreamFactory streams;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listFiles() {
        List<Map<String, Object>> result = new ArrayList<>();
        virtualFileRepository.findAll().forEach(vf -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", vf.getId());
            entry.put("filename", vf.getFilename());
            entry.put("contentType", vf.getContentType());
            entry.put("size", vf.getSize());
            entry.put("thumbnailId", vf.getThumbnailId());
            result.add(entry);
        });
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteFile(@PathVariable UUID id) {
        Map<String, Object> response = new HashMap<>();
        Optional<VirtualFile> opt = virtualFileRepository.findById(id);
        if (opt.isEmpty()) {
            response.put("success", false);
            response.put("message", "File not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        VirtualResource vr = virtualResourceRepository.findByFileId(id);
        if (vr != null) {
            virtualResourceRepository.delete(vr);
        }
        virtualFileRepository.deleteById(id);
        response.put("success", true);
        response.put("message", "File deleted");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/stream/{id}")
    public void streamFile(@PathVariable UUID id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<VirtualFile> opt = virtualFileRepository.findById(id);
        if (opt.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found");
            return;
        }
        VirtualFile vf = opt.get();

        String contentType = vf.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        response.setContentType(contentType);
        response.setHeader("Accept-Ranges", "bytes");

        long fileSize = vf.getSize();
        long start = 0;
        long end = fileSize - 1;

        String rangeHeader = request.getHeader("Range");
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] parts = rangeHeader.substring(6).split("-");
            try {
                start = Long.parseLong(parts[0].trim());
                if (parts.length > 1 && !parts[1].isBlank()) {
                    end = Long.parseLong(parts[1].trim());
                }
            } catch (NumberFormatException e) {
                response.sendError(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                return;
            }
            end = Math.min(end, fileSize - 1);
            long contentLength = end - start + 1;
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            response.setContentLengthLong(contentLength);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(fileSize);
        }

        long bytesToWrite = end - start + 1;
        try (VirtualFileStream in = streams.open(vf);
             OutputStream out = response.getOutputStream()) {
            if (start > 0) in.skip(start);
            byte[] buffer = new byte[65536];
            int read;
            while (bytesToWrite > 0 && (read = in.read(buffer, 0, (int) Math.min(buffer.length, bytesToWrite))) != -1) {
                out.write(buffer, 0, read);
                bytesToWrite -= read;
            }
        } catch (Exception e) {
            log.error("Error streaming file {}: {}", id, e.getMessage(), e);
        }
    }
}
