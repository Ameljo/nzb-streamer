package org.example;

import org.nzbstreamer.client.NzbStreamerClient;
import org.nzbstreamer.entity.VirtualFileMapper;
import org.nzbstreamer.model.Segment;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.model.VirtualFileChunk;
import org.nzbstreamer.repository.VirtualFileRepository;
import org.nzbstreamer.streams.VirtualFileStream;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manual check, not a JUnit test: compares real bytes of a real file against a local reference
 * copy, after a sequence of seeks on one {@code openStream()} (StreamingSource), the way a real
 * player seeking around one open connection would.
 *
 * <p>The reference copy comes from {@code POST /api/nzb/download} — the same NZB, decoded once and
 * written whole to {@code downloads/<filename>}, with no HTTP ranges and no player involved. This
 * check seeks the same stream to several positions, one after another — some forward, some
 * backward, crossing segment boundaries — and after each seek reads bytes and compares them
 * against the same range of the reference file. That exercises the restart/window-discard path in
 * {@code AbstractSegmentSource.seek()} directly, which a single fresh-stream-per-position read
 * never touches.</p>
 *
 * <p>The whole sequence is repeated {@code repeats} times, each time on a fresh stream and a fresh
 * load of the {@link VirtualFile} entity (the same way a new HTTP request would load it). If a
 * position is sometimes a MATCH and sometimes a MISMATCH across repeats, that points at something
 * that varies between runs, not a fixed wrong value.</p>
 *
 * <p>This downloads real segments from the real news server — it costs real bandwidth. Run with
 * {@code -Dspring.profiles.active=local}, and give the file id as the first argument and the
 * number of repeats as the second, or it uses the id from the VLC log and 3 repeats.</p>
 */
public class StreamingCorrectnessCheck {

    private static final int SAMPLE_LENGTH = 64;

    public static void main(String[] args) throws Exception {
        UUID id = UUID.fromString(args.length > 0 ? args[0] : "7f062ca4-695d-44b2-a0d2-16f5a285d542");
        int repeats = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        ConfigurableApplicationContext context = new SpringApplicationBuilder(Main.class)
                .web(WebApplicationType.NONE)
                .run(args);
        try {
            VirtualFileRepository files = context.getBean(VirtualFileRepository.class);
            NzbStreamerClient client = context.getBean(NzbStreamerClient.class);

            VirtualFile file = VirtualFileMapper.toLib(files.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("no file " + id)));
            File reference = new File("downloads", file.filename());
            if (!reference.isFile()) {
                throw new IllegalStateException("no local reference file at "
                        + reference.getAbsolutePath() + " -- download it first with POST"
                        + " /api/nzb/download");
            }
            System.out.printf("%s: %d bytes, reference file %d bytes%n%n", file.filename(),
                    file.getSize(), reference.length());

            // Checks the raw segment order as loaded, with no network involved, before spending
            // any bandwidth on the seek checks below.
            for (int attempt = 1; attempt <= repeats; attempt++) {
                VirtualFile orderFile = VirtualFileMapper.toLib(files.findById(id).orElseThrow());
                printSegmentOrder(attempt, orderFile);
            }
            System.out.println();

            // A mix of forward and backward jumps, like a player seeking around, not one
            // monotonic sweep.
            long size = file.getSize();
            long[] seeks = {0, size - 200_000, size / 3, size / 2, size / 3 + 500_000, 120};

            for (int attempt = 1; attempt <= repeats; attempt++) {
                System.out.printf("--- attempt %d/%d ---%n", attempt, repeats);
                // A fresh load of the entity, the same way a new HTTP request loads it.
                VirtualFile attemptFile = VirtualFileMapper.toLib(files.findById(id).orElseThrow());
                try (VirtualFileStream stream = client.openStream(attemptFile)) {
                    for (long position : seeks) {
                        checkSeek(stream, reference, position);
                    }
                }
                System.out.println();
            }
        } finally {
            context.close();
        }
    }

    /**
     * Prints whether the segment list of the file's chunk is actually sorted by {@code number} as
     * loaded, and where the first place it isn't sits, if any. No network access: this only reads what
     * came back from the database.
     */
    private static void printSegmentOrder(int attempt, VirtualFile file) {
        VirtualFileChunk chunk = file.getChunks().isEmpty() ? null : file.getChunks().get(0);
        if (chunk == null) {
            System.out.printf("attempt %d: file has no chunks%n", attempt);
            return;
        }
        List<Segment> segments = chunk.segments();
        List<String> numbers = segments.stream().map(s -> s.getNumber().toString())
                .collect(Collectors.toList());

        int firstOutOfOrder = -1;
        for (int i = 1; i < segments.size(); i++) {
            if (segments.get(i).getNumber().compareTo(segments.get(i - 1).getNumber()) <= 0) {
                firstOutOfOrder = i;
                break;
            }
        }

        System.out.printf("attempt %d: %d segments, first 10 numbers: %s%n", attempt,
                segments.size(), numbers.subList(0, Math.min(10, numbers.size())));
        if (firstOutOfOrder < 0) {
            System.out.println("  sorted by number: yes");
        } else {
            int from = Math.max(0, firstOutOfOrder - 2);
            int to = Math.min(numbers.size(), firstOutOfOrder + 3);
            System.out.printf("  sorted by number: NO — breaks at index %d, numbers around it: %s%n",
                    firstOutOfOrder, numbers.subList(from, to));
        }
    }

    private static void checkSeek(VirtualFileStream stream, File reference, long position)
            throws Exception {
        byte[] expected = readReference(reference, position);

        stream.seek(position);
        byte[] actual = stream.readNBytes(SAMPLE_LENGTH);

        boolean match = Arrays.equals(expected, actual);
        System.out.printf("  seek to %d: %s%n", position, match ? "MATCH" : "MISMATCH");
        if (!match) {
            System.out.println("    expected: " + hex(expected));
            System.out.println("    actual:   " + hex(actual));
        }
    }

    private static byte[] readReference(File file, long position) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(position);
            byte[] buffer = new byte[SAMPLE_LENGTH];
            int read = raf.read(buffer);
            return read == SAMPLE_LENGTH ? buffer : Arrays.copyOf(buffer, Math.max(read, 0));
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }
}
