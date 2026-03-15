package org.nzbstreamer.model;

import java.io.File;

public record DownloadResult(
        String fileName,
        boolean success,
        String errorMessage
) {
    public static DownloadResult success(String fileName) {
        return new DownloadResult(fileName, true, null);
    }

    public static DownloadResult failed(String fileName, String errorMessage) {
        return new DownloadResult(fileName, false, errorMessage);
    }
}
