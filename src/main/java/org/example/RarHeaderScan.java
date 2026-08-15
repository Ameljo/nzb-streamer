package org.example;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.VirtualFile;
import org.nzbstreamer.parser.NzbParserFactory;
import org.nzbstreamer.rar.RarArchive;
import org.nzbstreamer.rar.RarBlock;
import org.nzbstreamer.rar.RarFileEntry;
import org.nzbstreamer.rar.RarHeaderParser;
import org.nzbstreamer.rar.RarParseException;
import org.nzbstreamer.repository.ApplicationContextUtil;
import org.nzbstreamer.service.NNTPClientFactory;
import org.nzbstreamer.service.SegmentFetcher;
import org.nzbstreamer.service.UsenetConnectionPool;
import org.nzbstreamer.service.UsenetDownloadService;
import org.nzbstreamer.utils.NzbUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prints the RAR headers of an archive.
 *
 * <p>The argument selects one of two sources:</p>
 *
 * <ul>
 *   <li>a {@code .rar} file on the disk. The example reads it directly. It needs nothing more;</li>
 *   <li>a {@code .nzb} file. The example reads each archive in it from the news server.</li>
 * </ul>
 *
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=org.example.RarHeaderScan -Dexec.args=downloads/test.rar
 * mvn -q compile exec:java -Dexec.mainClass=org.example.RarHeaderScan -Dexec.args=downloads/test.nzb
 * </pre>
 *
 * <p>The NZB source needs the properties {@code usenet.server}, {@code usenet.port},
 * {@code usenet.username} and {@code usenet.password} in {@code application-local.properties}. It
 * does not need the database.</p>
 *
 * <p>To examine the structure in a debugger, put a breakpoint on the first line of
 * {@link #report}.</p>
 *
 * <p>To see the time of each step of a download, add this option to the JVM:</p>
 *
 * <pre>
 * -Dlogback.configurationFile=logback-trace.xml
 * </pre>
 */
public final class RarHeaderScan {

    private static final Logger log = LogManager.getLogger(RarHeaderScan.class);

    /** The example uses this file when the caller gives no argument. */
    private static final String DEFAULT_TARGET = "downloads/test.rar";

    /** This file gives the address and the account of the news server. */
    private static final String USENET_PROPERTIES = "application-local.properties";

    private RarHeaderScan() {
    }

    public static void main(String[] args) throws Exception {
        Path target = Path.of(args.length > 0 ? args[0] : DEFAULT_TARGET);
        if (!Files.exists(target)) {
            log.error("no such file: {}", target.toAbsolutePath());
            return;
        }

        if (target.toString().toLowerCase().endsWith(".nzb")) {
            scanNzb(target);
        } else {
            scanFile(target);
        }
    }

    /** Reads an archive from the disk. */
    private static void scanFile(Path path) throws IOException {
        long startedAt = System.nanoTime();
        RarArchive archive;
        try (InputStream stream = Files.newInputStream(path)) {
            archive = new RarHeaderParser().parse(stream);
        } catch (RarParseException e) {
            log.info("{}: not a RAR archive - {}", path.getFileName(), e.getMessage());
            return;
        }
        report(path.getFileName().toString(), Files.size(path), archive, millisecondsSince(startedAt));
    }

    /** Reads each archive of an NZB file from the news server. */
    private static void scanNzb(Path path) throws IOException {
        try (AnnotationConfigApplicationContext context = startUsenetContext()) {
            Nzb nzb;
            try (InputStream stream = Files.newInputStream(path)) {
                nzb = NzbParserFactory.createParser().parse(stream);
            } catch (Exception e) {
                log.error("cannot read the NZB file {}: {}", path, e.getMessage());
                return;
            }

            // The subject of an obfuscated post gives no file name. Thus this example does not use
            // the name to find the archives. It gives each file to the parser. The parser reads the
            // signature and refuses the files that are not RAR archives.
            for (NzbFile nzbFile : nzb.getFiles()) {
                scanVolume(nzbFile, NzbUtils.sanitizeFileName(nzbFile.getSubject()));
            }
        }
    }

    private static void scanVolume(NzbFile nzbFile, String name) {
        VirtualFile volume = new VirtualFile(nzbFile.getSize(), name, nzbFile);

        long startedAt = System.nanoTime();
        RarArchive archive;
        try (InputStream stream = volume.getInputStream()) {
            archive = new RarHeaderParser().parse(stream);
        } catch (RarParseException e) {
            log.info("{} ({} bytes): not a RAR archive - {}", name, nzbFile.getSize(), e.getMessage());
            return;
        } catch (Exception e) {
            log.error("{}: cannot read the headers: {}", name, e.getMessage());
            return;
        }

        log.info("{}: {} segments", name, nzbFile.getSegments().getSegment().size());
        report(name, nzbFile.getSize(), archive, millisecondsSince(startedAt));
    }

    private static void report(String name, long size, RarArchive archive, long milliseconds) {
        log.info("{} ({} bytes): {} format, {} files, parsed in {} ms", name, size,
                archive.format(), archive.entries().size(), milliseconds);
        log.info("{}: read {} bytes, skipped {} bytes, endOfArchive={}, truncated={}", name,
                archive.bytesRead(), archive.bytesSkipped(), archive.endOfArchive(),
                archive.truncated());

        for (RarBlock block : archive.blocks()) {
            if (block instanceof RarFileEntry entry) {
                log.info("    {} header@{} headerSize={} data@{} packed={} unpacked={} m{} crc={} {}{}{}{}",
                        entry.type(), entry.headerOffset(), entry.headerSize(), entry.dataOffset(),
                        entry.packedSize(), entry.unpackedSize(), entry.method(),
                        entry.crc32() == null ? "-" : String.format("%08X", entry.crc32()),
                        entry.name(),
                        entry.directory() ? " [directory]" : "",
                        entry.splitBefore() ? " [continues from the previous volume]" : "",
                        entry.splitAfter() ? " [continues in the next volume]" : "");
            } else {
                log.info("    {} header@{} headerSize={} data@{} dataSize={} flags=0x{}",
                        block.type(), block.headerOffset(), block.headerSize(), block.dataOffset(),
                        block.dataSize(), Long.toHexString(block.flags()));
            }
        }

        // The blocks must fill the volume with no gap. A difference here shows a lost block.
        long lastBlockEnd = archive.blocks().isEmpty() ? 0
                : archive.blocks().get(archive.blocks().size() - 1).dataOffset()
                + archive.blocks().get(archive.blocks().size() - 1).dataSize();
        if (archive.endOfArchive() && lastBlockEnd != size) {
            log.warn("{}: the blocks stop at {} but the archive has {} bytes", name, lastBlockEnd,
                    size);
        }
    }

    /**
     * Makes a small Spring context with only the beans that a download needs.
     *
     * <p>{@code JaxbNzbParser} and {@code DownloadSegmentsWorker} get {@link UsenetDownloadService}
     * from the static holder in {@link ApplicationContextUtil}. Thus a context is necessary. But
     * this example does not use the database, the web server or the WebDAV component. Thus it does
     * not start the full application.</p>
     */
    private static AnnotationConfigApplicationContext startUsenetContext() throws IOException {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new ResourcePropertySource("classpath:" + USENET_PROPERTIES));
        context.register(PropertySourcesPlaceholderConfigurer.class, ApplicationContextUtil.class,
                NNTPClientFactory.class, UsenetConnectionPool.class, SegmentFetcher.class,
                UsenetDownloadService.class);
        context.refresh();
        return context;
    }

    private static long millisecondsSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
