package org.nzbstreamer.utils;

public class NzbUtils {

    public static String sanitizeFileName(String fileName) {
        var parts = fileName.split("\"");
        if (parts.length  < 2) {
            return fileName;
        }
        return parts[1].replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    public static String normalizeMessageId(String messageId) {
        return messageId.startsWith("<") ? messageId : "<" + messageId + ">";
    }


    /**
     * Extracts a value for a given key from a yEnc header/trailer line.
     */
    public static String extractValue(String line, String key) {
        String pattern = key + "=";
        int start = line.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();
        int end = line.indexOf(' ', start);
        if (end == -1) end = line.length();
        return line.substring(start, end);
    }

    public static boolean isMediaType(String type) {
        return type.startsWith("video/") || type.startsWith("audio/") || type.startsWith("image/");
    }
}
