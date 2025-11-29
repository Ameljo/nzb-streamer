package org.model;

import java.io.File;

public record DownloadResult(
        String fileName,
        boolean success,
        String errorMessage,
        File outputFile
) {
    public static DownloadResult success(String fileName, File outputFile) {
        return new DownloadResult(fileName, true, null, outputFile);
    }

    public static DownloadResult failed(String fileName, String errorMessage) {
        return new DownloadResult(fileName, false, errorMessage, null);
    }
}
