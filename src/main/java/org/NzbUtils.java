package org;

public class NzbUtils {

    public static String sanitizeFileName(String fileName) {
        var parts = fileName.split("\"");
        return parts[1].replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    public static String normalizeMessageId(String messageId) {
        return messageId.startsWith("<") ? messageId : "<" + messageId + ">";
    }
}
