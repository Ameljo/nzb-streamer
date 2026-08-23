# AGENTS.md — nzb-parser / nzb-streamer

## Project Purpose
Spring Boot 3.2 / Java 21 application that parses NZB files, downloads yEnc-encoded segments from a Usenet (NNTP) server on-demand, and exposes the decoded media files as a **WebDAV drive** so media players can stream directly without full downloads.

## Package Layout
| Package | Role |
|---|---|
| `org.example` | Scratch / experimental classes (`Main`, `NNTPClientFactory`, RAR test harnesses). **`org.example.Main` is the Spring Boot entry point** but scans only `org.nzbstreamer`. |
| `org.nzbstreamer` | All production code. Sub-packages: `config`, `controller`, `decoder`, `exceptions`, `model`, `parser`, `repository`, `service`, `streams`, `transformers`, `utils`, `webdav`, `workers`. |

## Core Data Flow
1. `POST /api/nzb/upload` → `NzbController` → `NzbProcessingService.processNzbFile()`
2. `JaxbNzbParser` unmarshals the XML NZB into `Nzb` → `NzbFile` → `Segments` (models carry both `@XmlAttribute`/`@XmlElement` JAXB and `@Entity` JPA annotations on the same class).
3. `NzbTransformerFactory.getTransformer(NzbFile)` selects:
   - `NzbRarFileToVirtualFileTransformer` when Tika detects `application/x-rar-compressed`
   - `NzbFileToVirtualFileTransformer` for all other types (only stores the file if `NzbUtils.isMediaType()` returns true — i.e., `video/`, `audio/`, `image/` only; all other types are silently dropped).
4. `VirtualFile` entities (+ `VirtualResource` tree) are persisted to PostgreSQL.
5. WebDAV clients browse `/webdav/...`; `VirtualResourceFactory` → `VirtualResourceService` returns `VirtualFileResource` backed by `VirtualFileInputStream`.

## Streaming Architecture
`VirtualFileInputStream` is lazy: on the **first `read()`** it spins up a `DownloadSegmentsWorker` in a single-thread `ExecutorService`. The worker:
- Creates a fresh `NNTPClient` per stream via `NNTPClientFactory` (reads `nntp.properties`, **not** `application.properties`).
- Downloads yEnc segments one at a time into a `BlockingQueue<byte[]>`, buffering at most `download.worker.min-buffer-size` (default 4) ahead.
- Decodes each segment with `MultiPartDecoder` or `SingePartDecoder` (yEnc CRC validation is currently commented out).
- Uses Java 21 virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) inside `UsenetDownloadService` for parallel segment fetching in the bulk-download path.

## Configuration Split
- `application.properties` — template; placeholder values (`YOUR_USENET_SERVER`, etc.).
- `application-local.properties` — real local dev values; activate with `-Dspring.profiles.active=local`.
- `nntp.properties` — NNTP credentials loaded directly by `NNTPClientFactory` (bypasses Spring property binding entirely).

## Key Conventions
- **Filename extraction**: NZB subject lines look like `"filename.ext" (1/3)`. `NzbUtils.sanitizeFileName()` splits on `"` and sanitises the middle token — always pass the raw `subject` field through this before using as a filename.
- **Content-type routing**: `NzbUtils.isMediaType(type)` is the gate; anything that isn't video/audio/image is excluded from the virtual filesystem.
- **WebDAV singleton workaround**: Milton instantiates `VirtualResourceFactory` twice (once via its own servlet init, once by Spring). The class uses a static `instance` field to share state and delegates Spring beans via `ApplicationContextAware.setApplicationContext()` on the singleton.
- **WebDAV auth**: Credentials are hardcoded in `VirtualResourceFactory` (`usera`, `userb`, `userv` / `password`). Change them there.

## Build & Run
```bash
# Build fat JAR
mvn clean package

# Run locally (uses application-local.properties)
mvn spring-boot:run -Dspring-boot.run.profiles=local

# The app expects PostgreSQL at jdbc:postgresql://localhost:5432/nzb (user: nzb, pass: nzb) for local profile
# WebDAV endpoint: http://localhost:8080/webdav
# REST API:        http://localhost:8080/api/nzb/upload  (multipart POST, param "file")
```

## Known WIP / Caveats
- RAR archive item enumeration in `NzbRarFileToVirtualFileTransformer` is **commented out** — RAR files are detected and opened via 7-zip but inner files are not yet surfaced as `VirtualFile` records.
- `VirtualFileInputStream.skip()` has a TODO: it reschedules the worker but does not skip bytes cheaply.
- `YencDecoderTest.java` exists but is empty.
- `org.example` classes are not component-scanned; use them only as standalone test harnesses or migrate to `org.nzbstreamer`.

