package org.example;

import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypes;

import java.lang.reflect.Method;
import java.util.List;

public class MagicBytesExtractor {

    public static byte[][] getMagicBytes(String contentType) throws Exception {
        MimeTypes mimeTypes = MimeTypes.getDefaultMimeTypes();
        MediaType mediaType = MediaType.parse(contentType);

        MimeType mimeType = mimeTypes.forName(mediaType.toString());

        // Use reflection to access the non-public getMagics() method
        Method getMagicsMethod = MimeType.class.getDeclaredMethod("getMagics");
        getMagicsMethod.setAccessible(true);
        List<?> magics = (List<?>) getMagicsMethod.invoke(mimeType);

        if (magics.isEmpty()) {
            return new byte[0][];
        }

        byte[][] magicBytes = new byte[magics.size()][];
        for (int i = 0; i < magics.size(); i++) {
            Object magic = magics.get(i);
            Method getDataMethod = magic.getClass().getMethod("getData");
            magicBytes[i] = (byte[]) getDataMethod.invoke(magic);
        }

        return magicBytes;
    }

    public static void main(String[] args) throws Exception {
        byte[][] magics = getMagicBytes("audio/mp3");

        for (byte[] magic : magics) {
            System.out.print("Magic bytes: ");
            for (byte b : magic) {
                System.out.printf("0x%02X ", b);
            }
            System.out.println();
        }
    }
}
