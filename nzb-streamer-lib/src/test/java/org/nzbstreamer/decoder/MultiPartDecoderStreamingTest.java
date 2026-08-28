package org.nzbstreamer.decoder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Tests {@link MultiPartDecoder#decode(java.io.Reader, BlockingQueue, int, int, int)} against real
 * yEnc-encoded input, since the higher-level stream tests use already-decoded fake segments and
 * never actually exercise yEnc parsing or the skip/trim window.
 */
class MultiPartDecoderStreamingTest {

    /** Bytes that use the full 0-255 range, so encoding hits escaped characters too. */
    private static byte[] testBytes(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) i;
        }
        return data;
    }

    private static String encodeYenc(byte[] data) {
        StringBuilder line = new StringBuilder();
        for (byte value : data) {
            int plain = ((value & 0xFF) + 42) & 0xFF;
            if (plain == 0 || plain == 10 || plain == 13 || plain == 61) {
                line.append('=').append((char) ((plain + 64) & 0xFF));
            } else {
                line.append((char) plain);
            }
        }
        return line.toString();
    }

    private static String article(byte[] data) {
        return "=ybegin part=1 line=128 size=" + data.length + " name=test.bin\r\n"
                + "=ypart begin=1 end=" + data.length + "\r\n"
                + encodeYenc(data) + "\r\n"
                + "=yend size=" + data.length + " part=1 pcrc32=00000000\r\n";
    }

    private static byte[] drain(BlockingQueue<byte[]> queue) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : queue) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("decodes the whole article, unchanged, through the queue")
    void decodesWholeArticle() throws IOException, InterruptedException {
        byte[] data = testBytes(300);
        BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

        byte[] result = new MultiPartDecoder().decode(new StringReader(article(data)), queue, 64, 0,
                data.length);

        assertArrayEquals(data, result);
        assertArrayEquals(data, drain(queue));
    }

    @Test
    @DisplayName("skip and trim give exactly the middle window of the article")
    void skipAndTrimGiveTheMiddleWindow() throws IOException, InterruptedException {
        byte[] data = testBytes(300);
        BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        int skip = 50;
        int trim = 100;

        byte[] result = new MultiPartDecoder().decode(new StringReader(article(data)), queue, 64, skip,
                trim);

        byte[] expected = Arrays.copyOfRange(data, skip, skip + trim);
        assertArrayEquals(data, result);
        assertArrayEquals(expected, drain(queue));
    }

    @Test
    @DisplayName("a skip and trim that lines up with the start of the article, like position 0 of a file")
    void skipZeroGivesThePrefix() throws IOException, InterruptedException {
        byte[] data = testBytes(1000);
        BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        int trim = 200;

        byte[] result = new MultiPartDecoder().decode(new StringReader(article(data)), queue, 64, 0,
                trim);

        byte[] expected = Arrays.copyOfRange(data, 0, trim);
        assertArrayEquals(data, result);
        assertArrayEquals(expected, drain(queue));
    }
}
