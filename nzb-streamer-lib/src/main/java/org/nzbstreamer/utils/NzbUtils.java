package org.nzbstreamer.utils;

import java.util.List;

public class NzbUtils {

    private static final List<String> REPAIR_OR_METADATA_EXTENSIONS =
            List.of(".par2", ".sfv", ".nfo", ".md5", ".diz");

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

    /**
     * True when the subject clearly names a repair or metadata file (PAR2, SFV, NFO, ...), never
     * the actual media. This checks the name only, not the content, so it must only be used to
     * decide a post is definitely NOT media -- never to decide a post IS media. An obfuscated
     * post's real name will not match any of these, and falls through to content-based detection
     * exactly as before; this only skips the posts that were always going to be discarded anyway,
     * before spending a network round trip to find that out.
     */
    public static boolean isRepairOrMetadataFile(String subject) {
        String lower = sanitizeFileName(subject).toLowerCase();
        return REPAIR_OR_METADATA_EXTENSIONS.stream().anyMatch(lower::contains);
    }
}
