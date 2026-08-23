# Code Review — nzb-parser / nzb-streamer

Review date: 2026-08-15 · Branch: `feat/chunked-virtual-file` · Scope: all of `src/`, config, repo layout (~7,000 LOC Java)

## How to use this document

Each item has a stable ID (`D1`, `A3`, …), a checkbox, the files involved, what actually
goes wrong, why it matters, a concrete fix, and how to verify it. Work top-down within a
section, or follow the suggested order in [Where to start](#where-to-start).

Items are tagged with effort: **S** = under an hour · **M** = half a day · **L** = multi-day.

Line numbers refer to the state of the tree at review time. They will drift as you edit —
the surrounding code quotes are there so you can still find the spot.

---

## Table of contents

- [Where to start](#where-to-start)
- [A. Defects](#a-defects) — bugs, not opinions
- [B. Design](#b-design) — structure and coupling
- [C. Concurrency](#c-concurrency)
- [D. Code quality](#d-code-quality)
- [E. Configuration](#e-configuration)
- [F. Repository, tests and docs](#f-repository-tests-and-docs)
- [G. What is already good](#g-what-is-already-good)

---

## Where to start

If you do nothing else, do these five. They are the ones with user-visible consequences
and they are all self-contained.

1. **[A2](#a2--browsing-a-folder-downloads-every-file-in-it-and-never-stops)** — folder listing leaks threads and bandwidth forever
2. **[A1](#a1--webdav-authentication-does-not-exist)** — WebDAV auth is a stub that accepts everyone
3. **[A3](#a3--range-responses-can-overrun-content-length)** — range responses can overrun `Content-Length`
4. **[C1](#c1--busy-wait-instead-of-back-pressure)** — replace the busy-wait with a bounded queue
5. **[B1](#b1--service-locator-hides-dependencies)** — remove `ApplicationContextUtil` from the three call sites

A natural second wave: [A4](#a4--a-corrupt-byte-kills-the-stream-instead-of-triggering-a-retry) →
[A5](#a5--segment-integrity-is-never-verified) → [B4](#b4--two-stream-classes-sharing-a-duplicated-skeleton) →
[D1](#d1--usenetdownloadservice-is-three-services-in-one).

---

## A. Defects

These are behavioural bugs. Everything in this section is reproducible.

---

### A1 — WebDAV authentication does not exist

- [ ] **Fix** · **S** · `webdav/AbstractResource.java:42-99`, `webdav/VirtualResourceFactory.java:29-52`

**What happens**

```java
@Override
public Object authenticate(String user, String requestedPassword) {
    return "authenticated";          // line 43 — any user, any password
//  String p = VirtualWebDavFactory.credentialsMap.get(user);
//  ... 25 lines of the real implementation, commented out
}
```

Milton treats any non-`null` return from `authenticate` as success, so every credential
pair is accepted. `authenticate(DigestResponse)` (line 59) does the same — it returns the
request object itself, which is never `null`.

Then `authorise()` (line 92) makes it moot anyway:

```java
if (getName().matches(".*\\.(mkv|mp4|avi|mov)$")) {
    return true;                     // anonymous access, no Auth object required
}
```

Meanwhile `VirtualResourceFactory.credentialsMap` is populated from the `webdav.users`
config prefix via `@ConfigurationProperties` + `@PostConstruct init()` — and then read by
nothing. `addUser()` (line 50) is dead too.

**Why it matters**

Anything reachable on port 8080 can browse and download the entire library. If this box is
port-forwarded, or on a network you share, that is the whole security story. Separately,
`AGENTS.md:37` states that credentials are hardcoded as `usera`/`userb`/`userv` with
password `password` — describing code that does not exist, so the next reader (or agent)
starts from a false model.

**Fix**

Decide which of these you want, then make the code say it out loud:

*Option 1 — actually authenticate.* Restore the commented-out body, but read from the
already-populated `credentialsMap` rather than a static:

```java
@Override
public Object authenticate(String user, String requestedPassword) {
    String expected = VirtualResourceFactory.credentialsFor(user);
    if (expected != null && MessageDigest.isEqual(
            expected.getBytes(UTF_8), requestedPassword.getBytes(UTF_8))) {
        return user;                 // the principal, used by authorise()
    }
    log.warn("failed login for user {}", user);
    return null;
}

@Override
public boolean authorise(Request request, Request.Method method, Auth auth) {
    return auth != null && auth.getTag() != null;
}
```

Note this depends on [B6](#b6--the-milton-singleton-hack) being sorted out, otherwise you
are reading from whichever of the two factory instances happened to win.

*Option 2 — deliberately open.* Delete `credentialsMap`, `users`, `addUser`,
`@ConfigurationProperties`, the `webdav.users.*` properties and the commented blocks. Leave
one comment on `authenticate` saying the endpoint is intentionally unauthenticated and is
expected to be bound to localhost only. Then bind it to localhost in `application.properties`.

Either way, update `AGENTS.md:37`.

**Verify**

`curl -u nobody:wrong http://localhost:8080/webdav/` should return 401 under option 1.
A `PROPFIND` with no `Authorization` header should also be rejected — including for a
`.mkv`.

---

### A2 — Browsing a folder downloads every file in it, and never stops

- [ ] **Fix** · **M** · `service/VirtualResourceService.java:38-55`, `streams/VirtualFileInputStream.java:59-74,210-224`, `webdav/VirtualFileResource.java:32-41`

**What happens**

Listing a folder constructs one input stream per child:

```java
// VirtualResourceService.java:46
children.add(new VirtualFileResource(new VirtualFileInputStream(vf), folder));
```

`VirtualFileInputStream`'s constructor is not free:

```java
private VirtualFileInputStream(VirtualFile file, SegmentFetcher fetcher, ...) {
    ...
    seek(0);                                  // line 73
}

public void seek(long newPosition) {
    ...
    startWorkerAt(position);                  // line 158
}

private void startWorkerAt(long startPosition) {
    ...
    Thread worker = new Thread(new DownloadSegmentsWorker(...));
    worker.setDaemon(true);
    worker.start();                           // line 223
}
```

`DownloadSegmentsWorker.run()` then creates *its own* fixed pool of
`PARALLEL_DOWNLOADS` (8) threads and starts pulling segments, buffering `MAX_AHEAD` (16)
of them.

Nothing in this path ever calls `close()`. So `running` stays `true`, and once the queue is
full the worker parks in the polling loop:

```java
while (bufferQueue.size() >= maxAhead && running.get()) {
    Thread.sleep(QUEUE_FULL_SLEEP_MS);        // DownloadSegmentsWorker.java:96
}
```

…forever. The daemon flag means it will not block JVM shutdown, and that is the only
reason you have not noticed.

**Cost of one PROPFIND on a 20-file folder**

| Resource | Leaked per listing |
|---|---|
| Worker threads (spinning, permanent) | 20 |
| Download pool threads | 20 × 8 = 160 |
| Segments fetched | 20 × 16 = 320 (~200 MB at 640 KB/segment) |
| Pool permits held during the burst | up to 20 × 8, against a pool of 40 |

Refresh the folder in your file manager a few times and you exhaust
`usenet.pool-size`, and every real playback request then blocks on `permits.acquire()`.

**Root cause, and why the fix is small**

`VirtualFileResource` never wanted a stream. Look at what it does with the argument:

```java
public VirtualFileResource(VirtualFileInputStream inputStream, VirtualFolderResource parent) {
    super(parent, inputStream.getFile().filename());
    this.vf = inputStream.getFile();
    this.displayname = inputStream.getFile().filename();
    ...
    this.getcontentlength = inputStream.getFile().getSize();
}
```

Four calls, all `getFile()`. It needs the `VirtualFile` and nothing else — `sendContent()`
already opens its own stream when bytes are actually requested
(`VirtualFileResource.java:151`).

**Fix**

1. Change the constructor to take what it uses:

```java
public VirtualFileResource(VirtualFile file, VirtualFolderResource parent) {
    super(parent, file.filename());
    this.vf = file;
    this.displayname = file.filename();
    this.getcontentlength = file.getSize();
    this.getcontenttype = file.getContentType();
    ...
}
```

2. Update the two call sites in `VirtualResourceService` (lines 46 and 54) to pass
   `rChild.getFile()` / `r.getFile()`.

3. Make the leak unrepresentable rather than merely absent: move the eager
   `seek(0)` out of the constructor so construction never starts I/O. Start the worker
   lazily on the first `read()`:

```java
private boolean started;

@Override
public int read() throws IOException {
    if (!file.hasNext(position)) return -1;
    if (!started) { startWorkerAt(position); started = true; }
    ...
}
```

   This also settles the contract that [A6](#a6--a-test-that-passes-by-winning-a-race)
   currently contradicts.

**Verify**

Add a test with a counting fake fetcher: construct `VirtualFileResource` for a file,
assert zero segments were fetched and no new threads were created. Then, manually,
`PROPFIND` a folder and watch the thread count in JConsole stay flat.

---

### A3 — Range responses can overrun Content-Length

- [ ] **Fix** · **S** · `webdav/VirtualFileResource.java:150-162` (compare `controller/VirtualFileController.java:110-118`)

**What happens**

```java
long bytesToWrite = end - start + 1;
try (VirtualFileInputStream nzbStream = new VirtualFileInputStream(vf)) {
    if (start > 0) nzbStream.skip(start);
    int bufferLength = 65536;
    int read;
    int lengthToRead = Math.toIntExact(Math.min(bufferLength, bytesToWrite));  // computed ONCE
    byte[] buffer = new byte[lengthToRead];
    while ((read = nzbStream.read(buffer, 0, lengthToRead)) != -1 && bytesToWrite > 0) {
        out.write(buffer, 0, read);        // never clamped to bytesToWrite
        bytesToWrite -= read;
    }
```

Two problems in three lines:

1. `lengthToRead` is fixed before the loop, so every iteration asks for the same amount
   regardless of how much of the range is left.
2. `out.write(buffer, 0, read)` writes everything that was read. The `bytesToWrite > 0`
   guard is evaluated *after* the write has already happened.

For a request like `Range: bytes=0-100` on a large file, the loop reads 101 bytes on the
first pass (correct, because `bytesToWrite` was 101 at construction) — that case is fine.
The failure case is any range longer than 64 KB whose length is not a multiple of 64 KB:
the last iteration reads a full 64 KB and writes all of it, overshooting the declared range
by up to 65,535 bytes.

**Why it matters**

The client was told `Content-Range`/`Content-Length` for the requested window. Writing more
either gets truncated by the container (harmless but wasteful), or desynchronises the
response body. Players that issue many small ranges while seeking — which is exactly the
workload this project exists for — are the ones that hit it.

**Fix**

`VirtualFileController.java:115` already has this right:

```java
while (bytesToWrite > 0 && (read = in.read(buffer, 0, (int) Math.min(buffer.length, bytesToWrite))) != -1) {
    out.write(buffer, 0, read);
    bytesToWrite -= read;
}
```

Do not fix it twice. Extract one helper and delete both copies — see
[B5](#b5--range-streaming-logic-is-duplicated).

**Verify**

`curl -r 0-99999 -o part.bin http://localhost:8080/webdav/<file>` then check
`part.bin` is exactly 100,000 bytes.

---

### A4 — A corrupt byte kills the stream instead of triggering a retry

- [ ] **Fix** · **S** · `decoder/AbstractYencDecoder.java:66,74`, `workers/DownloadSegmentsWorker.java:137-163`

**What happens**

The decoder signals corruption with unchecked exceptions:

```java
// AbstractYencDecoder.decodeLine, line 66
if (escaped) {
    throw new RuntimeException("Orphaned escape character at end of line");
}

// AbstractYencDecoder.validatePart, line 74
throw new RuntimeException("""
    Part %s CRC mismatch: ...""");
```

The retry loop catches only `IOException`:

```java
} catch (ClosedByInterruptException e) {
    throw e;
} catch (IOException e) {
    if (attempt == maxRetries || Thread.currentThread().isInterrupted()) throw e;
    ...
    Thread.sleep(100L * attempt);
}
```

So the single failure mode retries were designed for — a damaged or truncated article —
bypasses the retry entirely, propagates out of the `Callable`, and surfaces in
`VirtualFileInputStream.read()` as an `ExecutionException` that aborts the whole stream.

**Fix**

Give the decoder a checked exception in the `IOException` family:

```java
package org.nzbstreamer.decoder;

/** The bytes of an article are not valid yEnc, or they do not match their checksum. */
public class YencDecodeException extends IOException {
    public YencDecodeException(String message) { super(message); }
}
```

Throw it from both sites. `decodeLine` and `decode` already declare `throws IOException`,
so no signature changes are needed — the retry loop starts working immediately.

Consider also: a segment that fails all retries is a *hole*, not necessarily a dead stream.
Today `read()` throws and the playback ends. A future improvement is to return zero-filled
bytes for that segment and log loudly, so a scratched frame does not end the movie. Note it
as a decision rather than doing it silently.

**Verify**

Unit-test the decoder against an article with a trailing `=` and against one with a bad
`pcrc32`; assert `YencDecodeException`. Then test `DownloadSegmentsWorker` with a fetcher
that fails twice and succeeds on the third call, asserting three fetches and a good result.

---

### A5 — Segment integrity is never verified

- [ ] **Investigate, then fix** · **M** · `decoder/MultiPartDecoder.java:27-31`

**What happens**

```java
case String s when s.startsWith("=yend") -> {
    var trailer = YencTrailer.parse(s);
//  validatePart(crc.getValue(), trailer); //TODO fix validation failing for nfo files
    return output.toByteArray();
}
```

`MultiPartDecoder` is the decoder used by *every* download path
(`SegmentFetcher.fetch`, `fetchPrefix`, `UsenetDownloadService`). With the call commented
out, nothing anywhere checks that the bytes you received are the bytes that were posted.
The CRC is computed (`crc.update` runs per byte in `decodeLine`) and then discarded.

`SingePartDecoder` — which *does* call `validatePart` — is referenced by nothing
(see [D6](#d6--dead-code)).

**Why it matters**

Silent corruption is the worst failure mode in a streaming system: the player shows
artefacts or stops, and every log line says the download succeeded. You also lose the
ability to distinguish "this Usenet provider is missing parts" from "my decoder is wrong",
which is the diagnosis you will want the first time playback misbehaves.

**Fix**

First find out *why* nfo files fail. Likely candidates, in order of probability:

1. **They are single-part posts.** A single-part yEnc article has no `=ypart` line, and its
   `=yend` carries `crc32=` rather than `pcrc32=`. `validatePart` only checks
   `trailer.pcrc32()`, so a single-part article should skip cleanly — unless the post
   includes both, or includes `pcrc32` with a whole-file CRC.
2. **Line-ending handling.** `BufferedReader.readLine()` strips `\r\n`; if any nfo post
   contains a literal CR inside encoded data it will be silently dropped from the CRC.
3. **A `=y` sequence inside the data** being mistaken for a keyword line, because the
   `switch` matches on `startsWith` against the raw line.

Reproduce with a saved nfo article as a test fixture, decide which case it is, then:

```java
case String s when s.startsWith("=yend") -> {
    validatePart(crc.getValue(), YencTrailer.parse(s));   // uncommented
    return output.toByteArray();
}
```

and make `validatePart` handle the whole-file `crc32` case explicitly rather than by
omission. Depends on [A4](#a4--a-corrupt-byte-kills-the-stream-instead-of-triggering-a-retry)
so that a CRC failure retries rather than kills the stream.

**Verify**

Fixture-based decoder tests: a good multi-part article, one with a flipped byte, and a
real nfo post. All three should behave as documented.

---

### A6 — A test that passes by winning a race

- [ ] **Fix** · **S** · `src/test/java/org/nzbstreamer/streams/VirtualFileInputStreamTest.java:73-86`

**What happens**

```java
@Test
@DisplayName("a move of the cursor downloads nothing")
void seekDownloadsNothing() throws IOException {
    TestSegments segments = new TestSegments();
    try (VirtualFileInputStream stream = new VirtualFileInputStream(fileOfTwoVolumes(), segments)) {
        stream.seek(40);
        stream.skip(5);
        assertEquals(0, segments.downloaded.size(), "a move must not download a segment");
```

But `seek(40)` calls `startWorkerAt(40)`, which starts a thread that begins fetching
immediately. The assertion passes only because it usually executes before the worker's
first `fetch()` lands. On a loaded machine, or with a slower fake, it flakes.

The test also contradicts the class's own javadoc:

> `{@link #seek(long)} is the only function that changes the position. It stops the worker
> and starts a new worker at the new position.` — `VirtualFileInputStream.java:24`

So the class documents eager prefetch and the test asserts laziness. One of them is wrong;
right now the code is eager and the test is lucky.

**Fix**

Decide the contract. The lazy behaviour is what [A2](#a2--browsing-a-folder-downloads-every-file-in-it-and-never-stops)
needs anyway, so: make `seek()` record the position only, start the worker on first
`read()`, update the javadoc, and the test becomes deterministic and meaningful.

If instead you keep eager prefetch, the test must synchronise — e.g. have the fake fetcher
count down a `CountDownLatch` and assert it is *not* tripped within a bounded wait. Do not
leave a bare `assertEquals(0, …)` against a background thread.

**Verify**

Run the test 200 times (`mvn test -Dsurefire.rerunFailingTestsCount=0` in a loop, or a
`@RepeatedTest(200)` locally). It must never fail.

---

### A7 — Truncated downloads are reported to the client as normal EOF

- [ ] **Fix** · **S** · `streams/VirtualFileInputStream.java:104-115`, `controller/VirtualFileController.java:119-121`

**What happens**

When the worker dies early, the stream logs and returns `-1`:

```java
if (endOfSegments.get() && bufferQueue.isEmpty()) {
    log.error("{}: the worker stopped at position {} of {}, thus the file is not complete", ...);
    return -1;                            // indistinguishable from end of file
}
```

And the controller swallows the failure after headers have already been sent:

```java
} catch (Exception e) {
    log.error("Error streaming file {}: {}", id, e.getMessage(), e);
}                                          // response ends, status was already 200/206
```

**Why it matters**

The client believes it received the complete file. A media player will show a file that
simply stops; a `curl -o` will produce a short file with exit status 0. The information
that something went wrong exists only in your server log.

**Fix**

`read()` should throw rather than lie:

```java
if (endOfSegments.get() && bufferQueue.isEmpty()) {
    throw new IOException(file.filename() + ": the download stopped at " + position
            + " of " + file.getSize());
}
```

`-1` is then reserved for the one honest case, `!file.hasNext(position)`.

In the controller, an exception after the response has been committed cannot be turned into
a 500 — but you can stop pretending it succeeded by not catching it (let the container
abort the connection, which is the signal HTTP has for this), or by calling
`response.getOutputStream().close()` after logging so the client sees a truncated chunked
transfer rather than a clean end.

**Verify**

Point a fake fetcher at a file where segment 3 of 6 always fails; assert the client sees an
error rather than a 3-segment file.

---

## B. Design

---

### B1 — Service Locator hides dependencies

- [ ] **Fix** · **M** · `repository/ApplicationContextUtil.java`, `streams/VirtualFileInputStream.java:61,84`, `transformers/TikaNzbFileTransformer.java:91`, `parser/JaxbNzbParser.java:43`

**What happens**

```java
public VirtualFileInputStream(VirtualFile file) {
    this(file, ApplicationContextUtil.getBean(SegmentFetcher.class));
}
```

`ApplicationContextUtil` is a static, mutable global holding the Spring container. Three
classes reach into it to pull collaborators that do not appear in their signatures.

**Why it matters**

This is not a style preference — it has already cost you something concrete. Because
`SegmentFetcher` cannot be injected everywhere, the test suite works around it:

```java
// VirtualFileInputStreamTest.java:27
private static final class TestSegments extends SegmentFetcher {
    TestSegments() { super(null); }        // a real pool client, constructed with null
    @Override public byte[] fetch(...) { ... }
}
```

A test double that extends the production class and passes `null` to its constructor is a
signal, not a solution: it only works while `SegmentFetcher` happens not to touch `pool` in
its constructor. The day someone adds a field initialiser there, every stream test fails
with an NPE for reasons unrelated to what they are testing.

It also means: no class using the locator can be constructed in a plain unit test; the
static `context` field is a cross-test contamination hazard; and the dependency graph is
invisible to anyone reading a constructor.

**Fix**

1. Make `SegmentFetcher` an interface, and rename the current class to what it is:

```java
public interface SegmentFetcher {
    byte[] fetch(String messageId, String group) throws IOException, InterruptedException;
    byte[] fetchPrefix(String messageId, String group, int maxBytes) throws IOException, InterruptedException;
}

@Component
public class PooledSegmentFetcher implements SegmentFetcher { ... }
```

   Tests then implement the interface — no `super(null)`.

2. Delete the no-arg `VirtualFileInputStream(VirtualFile)` and `forHeaders(VirtualFile)`
   convenience constructors. Every caller (`VirtualResourceService`,
   `VirtualFileResource.sendContent`, `VirtualFileController.streamFile`) is a Spring bean
   or has access to one, so they can be handed the fetcher.

3. `TikaNzbFileTransformer` is already a `@Component` — give it a constructor parameter.

4. `JaxbNzbParser` should not need the locator at all once
   [B2](#b2--a-parser-that-talks-to-the-internet) is done.

5. Then delete `ApplicationContextUtil`. If one stubborn call site remains (Milton
   instantiating a class outside Spring), keep it but confine it to that single file and
   say so in a comment.

**Verify**

`grep -rn "ApplicationContextUtil" src/main` returns nothing, or one commented occurrence.
`VirtualFileInputStreamTest` no longer subclasses a production class.

---

### B2 — A parser that talks to the internet

- [ ] **Fix** · **M** · `parser/JaxbNzbParser.java:38-54`, `parser/NzbParserFactory.java`, `service/NzbProcessingService.java:52`

**What happens**

```java
@Override
public Nzb parse(InputStream input) throws NzbParseException {
    Nzb nzb = parseHeadersOnly(input);
    UsenetDownloadService downloadService = ApplicationContextUtil.getBean(UsenetDownloadService.class);
    downloadService.populateNzbFileSizes(nzb.getFiles().stream()
            .filter(file -> !NzbUtils.sanitizeFileName(file.getSubject()).contains(".nfo"))
            .toList());
    ...
}
```

A class called "parser", implementing an interface called `NzbParser`, with a method called
`parse(InputStream)`, opens NNTP connections and fetches one article per post.

**Why it matters**

- You cannot parse an NZB in a test without a news server and credentials.
- The failure mode is misreported: a Usenet timeout is thrown as `NzbParseException("Failed to parse NZB file")`.
- The good version is already sitting right below it. `parseHeadersOnly()` (line 61) is the
  honest parser, and its javadoc is excellent — it explains exactly why the `bytes`
  attribute is not trustworthy for seeking.

**Fix**

Make the pipeline visible in the orchestrating service instead of hidden in a parser:

```java
// NzbProcessingService
Nzb nzb = parser.parse(inputStream);            // pure: XML in, model out
segmentSizeResolver.resolve(nzb.getFiles());    // network: fills sizes and start positions
List<VirtualFile> files = transformer.transform(nzb);
virtualFileRepository.saveAll(files);
```

- Rename `parseHeadersOnly` → `parse`, and delete the old `parse`.
- Move `populateNzbFileSizes` (both overloads) out of `UsenetDownloadService` into a new
  `SegmentSizeResolver` — that is a coherent job with a name, and it is the only part of
  `UsenetDownloadService` still worth keeping (see [D1](#d1--usenetdownloadservice-is-three-services-in-one)).
- Move the `.nfo` filter to the resolver, and give it a comment explaining *why* nfo posts
  are excluded — right now that filter is a mystery to anyone who did not write it.

**Also here:** `NzbParserFactory.createParser()` returns `new JaxbNzbParser()` on every
call, and each construction builds a fresh `JAXBContext`:

```java
public JaxbNzbParser() {
    this.jaxbContext = JAXBContext.newInstance(Nzb.class);   // reflects over the whole model graph
}
```

`JAXBContext` is thread-safe by design and is *the* expensive object in JAXB — it is meant
to be created once per application. It is currently created on every upload, and twice per
request in `NzbController.download` (line 223). Make `JaxbNzbParser` a `@Component` and
inject it; the factory can then go away entirely.

**Verify**

A parser test that reads an NZB from `src/test/resources` with no Spring context and no
network.

---

### B3 — A factory that decides nothing

- [ ] **Fix** · **S** · `transformers/NzbTransformerFactory.java`, `transformers/NzbTransformer.java`, `transformers/NzbFileTransformer.java`, `transformers/NzbToStringTransformer.java`

**What happens**

```java
@Component
public class NzbTransformerFactory {
    private final NzbTransformer<List<VirtualFile>> tikaNzbFileTransformer =
            new TikaNzbFileTransformer();          // hand-constructed, though it is a @Component

    public NzbTransformer<List<VirtualFile>> getTransformer(Nzb nzb) {
        return tikaNzbFileTransformer;             // parameter ignored
    }
}
```

One product, an unused parameter, and a `new` that shadows the Spring-managed bean — so
there are two instances of `TikaNzbFileTransformer` in the application and the container's
copy is never used. (Which also means the constructor injection suggested in
[B1](#b1--service-locator-hides-dependencies) would silently not apply to the instance that
actually runs.)

Around it sit two interfaces for one idea — `NzbTransformer<T>` (takes `Nzb`) and
`NzbFileTransformer<T>` (takes `NzbFile`) — and `NzbToStringTransformer`, which implements
one of them for no caller.

**Why it matters**

A factory abstracts *variation*. With one product there is no variation to abstract, so the
class is a layer of indirection that a reader must traverse to discover that nothing
happens. The comment on `getTransformer` even explains why the selection logic is gone
(Tika now detects by content) — which is the argument for deleting the factory, written
inside the factory.

**Fix**

- Inject `TikaNzbFileTransformer` directly into `NzbProcessingService`; delete
  `NzbTransformerFactory`.
- Delete `NzbToStringTransformer`.
- Keep `NzbTransformer<T>` only if you can name the second implementation you expect. If you
  cannot, use the concrete type — you can extract the interface in the five minutes it takes,
  on the day it earns its place.
- `NzbFileTransformer` is referenced only by an unused import in `NzbProcessingService` and
  by `org.example/MetadataMain` (which is going away per [F1](#f1--committed-junk)). Delete it.

**Verify**

`mvn compile` after deletion; `grep -rn "TransformerFactory\|NzbFileTransformer" src/main`
comes back empty.

---

### B4 — Two stream classes sharing a duplicated skeleton

- [ ] **Fix** · **M** · `streams/VirtualFileInputStream.java`, `streams/VirtualFileRangeStream.java`

**What happens**

The two classes have byte-identical implementations of `skip`, `available`,
`markSupported`, `mark`, and `reset`, and near-identical `seek`. They differ only in where
bytes come from:

| | `VirtualFileInputStream` | `VirtualFileRangeStream` |
|---|---|---|
| Strategy | background worker, 8 parallel, 16 ahead | fetch on demand, 64 KB prefix first |
| Used by | WebDAV `sendContent`, `/api/files/stream` | Tika header scanning |
| `read(byte[],int,int)` | **not overridden** | overridden |

That last row is the one that costs you: `InputStream`'s default
`read(byte[], off, len)` is a loop over single-byte `read()` calls. So the playback path —
the one moving gigabytes through a 64 KB buffer — makes one virtual call, one bounds check
and one `hasNext` per byte, while the header path that moves kilobytes is the one that got
the bulk implementation. It is backwards.

**Why it matters**

Duplication is where divergence lives. [A3](#a3--range-responses-can-overrun-content-length)
is exactly this: the same range loop written twice, correct in one copy.

**Fix**

One stream, one strategy — the classic Strategy pattern, where the varying part is
"how do I get the bytes covering this position":

```java
/** Gives the bytes of a file around a position. Implementations differ in what they fetch ahead. */
interface SegmentSource extends Closeable {
    /** Bytes available at {@code position}, or an empty array at the end of the file. */
    byte[] at(long position) throws IOException;
    /** Tells the source the reader jumped. It may discard work in flight. */
    void moveTo(long position);
}

final class PrefetchingSource implements SegmentSource { ... }   // today's worker
final class OnDemandSource   implements SegmentSource { ... }   // today's range stream
```

`VirtualFileStream` then owns position, mark/reset, `skip`, `available`, and a proper
`read(byte[],int,int)` that copies in bulk — written once, correct once, fast for both
callers. Roughly 60 duplicated lines disappear and the choice becomes explicit at the call
site: `new VirtualFileStream(file, new OnDemandSource(file, fetcher))`.

If that is more surgery than you want right now, the minimum useful step is: **override
`read(byte[], int, int)` in `VirtualFileInputStream`**, copying from `currentBytes` in bulk.
That is a contained change with a measurable win.

**Verify**

Benchmark `readAllBytes()` on a fake 100 MB file before and after the bulk read.
Run the existing stream tests against both sources.

---

### B5 — Range streaming logic is duplicated

- [ ] **Fix** · **S** · `webdav/VirtualFileResource.java:126-167`, `controller/VirtualFileController.java:68-122`

**What happens**

Two implementations of "parse a range, set the headers, copy that window to the output",
one of which is wrong ([A3](#a3--range-responses-can-overrun-content-length)). They also
disagree on details: the controller sets `Content-Range` and 206; the WebDAV resource
relies on Milton and sets only `Accept-Ranges`.

**Fix**

Extract the copy loop into a small, tested helper — this is the piece with the arithmetic:

```java
public final class ByteRanges {
    private ByteRanges() {}

    /** Copies exactly {@code count} bytes from {@code in} to {@code out}, or fewer at end of file. */
    public static long copy(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = count;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return count - remaining;
    }
}
```

Both call sites become three lines. Unit-test `copy` for: count smaller than the buffer,
count larger, count exactly one buffer, and a stream that ends early.

---

### B6 — The Milton singleton hack

- [ ] **Fix** · **M** · `webdav/VirtualResourceFactory.java:24-64`, `config/MiltonConfig.java:21`

**What happens**

Milton is told to construct the factory by class name:

```java
registration.addInitParameter("resource.factory.class", "org.nzbstreamer.webdav.VirtualResourceFactory");
```

So the class is instantiated twice — once by Spring (`@Component`) and once reflectively by
Milton. The code copes with a static:

```java
private static VirtualResourceFactory instance;

public VirtualResourceFactory() {
    if (instance == null) { instance = this; }
    else { credentialsMap.putAll(instance.credentialsMap); }
}

@Override
public void setApplicationContext(ApplicationContext ctx) {
    instance.virtualResourceService = ctx.getBean(VirtualResourceService.class);
    //  ^^^^ writes into the static, not into `this`
}
```

**Why it matters**

It works only because of construction order: whichever instance is built first becomes
`instance`, and Spring's `setApplicationContext` then configures *that* one. If Milton's
filter initialises before the Spring bean, `getResource` on the live object dereferences a
`virtualResourceService` that was set on a different object. It is also why
[A1](#a1--webdav-authentication-does-not-exist)'s `credentialsMap` is ambiguous — there are
two maps and the copy happens only in one direction, once.

Additionally, `@ConfigurationProperties` on a type that something else instantiates
reflectively means half the instances get bound properties and half do not.

**Fix**

Give Milton a factory you built, rather than a class name to construct. Milton's
`FilterRegistrationBean` route supports supplying a configured `HttpManager`/
`ResourceFactory` — check the API on `milton-server-ce 4.0.0.1129-Beta` for the exact hook
(`MiltonFilter` reads `milton.configurator` / an `HttpManagerBuilder`), then:

```java
@Bean
public FilterRegistrationBean<MiltonFilter> miltonFilter(VirtualResourceFactory factory) {
    HttpManagerBuilder builder = new HttpManagerBuilder();
    builder.setResourceFactory(factory);          // the Spring bean, fully wired
    ...
}
```

If that turns out not to be reachable in this Milton build, the fallback is still an
improvement: keep one instance, but make it explicit and safe —

```java
private static volatile VirtualResourceFactory instance;   // set once, in @PostConstruct
@Override public void setApplicationContext(ApplicationContext ctx) {
    this.virtualResourceService = ctx.getBean(VirtualResourceService.class);  // `this`, not `instance`
}
@Override public Resource getResource(String host, String url) {
    VirtualResourceService service = this.virtualResourceService != null
            ? this.virtualResourceService : instance.virtualResourceService;
    return service.getResource(host, url);
}
```

…with a comment stating plainly that Milton constructs a second copy and why.

**Verify**

Log the identity hash of `this` in the constructor and in `getResource`. Confirm the object
serving requests is the one that received the `ApplicationContext`.

---

### B7 — An entity that opens sockets

- [ ] **Fix** · **S** · `model/VirtualFile.java:13,135-137`

**What happens**

```java
package org.nzbstreamer.model;
import org.nzbstreamer.streams.VirtualFileInputStream;   // model → streams

@Entity
public class VirtualFile {
    public InputStream getInputStream() throws Exception {
        return new VirtualFileInputStream(this);
    }
}
```

`streams` depends on `model` (it reads `VirtualFile.locate`), and `model` depends on
`streams`. A cycle between your two most important packages, so neither can be read,
tested, or extracted in isolation.

The method is also a `getX()` that opens network connections and pulls from a Spring
context via the locator, and it declares `throws Exception`, which erases every distinction
a caller might act on.

**Why it matters**

`VirtualFile` is otherwise the best-designed class in the project — `locate()` and the
`Location` record are genuinely good ([G](#g-what-is-already-good)). This one method drags
JPA, Spring, threads and NNTP into what should be a pure map from file position to segment.

**Fix**

Delete `getInputStream()`. Its callers (5 references) get a `SegmentFetcher`-aware factory
instead — a one-line method on the service that already holds the fetcher:

```java
@Component
public class VirtualFileStreams {
    private final SegmentFetcher fetcher;
    public InputStream open(VirtualFile file) { return new VirtualFileInputStream(file, fetcher); }
    public InputStream openForHeaders(VirtualFile file) { return VirtualFileInputStream.forHeaders(file, fetcher); }
}
```

Then `model` imports nothing from `streams`, and the cycle is gone.

**Verify**

`grep -rn "org.nzbstreamer.streams" src/main/java/org/nzbstreamer/model/` is empty.

---

## C. Concurrency

---

### C1 — Busy-wait instead of back-pressure

- [ ] **Fix** · **S** · `workers/DownloadSegmentsWorker.java:48,95-97`

**What happens**

```java
private static final int QUEUE_FULL_SLEEP_MS = 10;
...
while (bufferQueue.size() >= maxAhead && running.get()) {
    Thread.sleep(QUEUE_FULL_SLEEP_MS);
}
```

The queue is a `LinkedBlockingQueue` created *unbounded* by the caller
(`VirtualFileInputStream.java:213`), so the worker has to enforce the bound itself by
polling.

**Why it matters**

You are hand-rolling the exact feature the class you chose already provides. Costs: a
thread parked in a 10 ms poll loop per active stream (and permanently per leaked stream —
see [A2](#a2--browsing-a-folder-downloads-every-file-in-it-and-never-stops)), plus up to
10 ms of avoidable latency on every segment handoff once the reader catches up.

**Fix**

Bound the queue at construction and let `put()` block:

```java
// VirtualFileInputStream.startWorkerAt
bufferQueue = new LinkedBlockingQueue<>(maxAhead);
```

```java
// DownloadSegmentsWorker.run — the polling loop disappears entirely
while (file.hasNext(position) && running.get()) {
    VirtualFile.Location location = file.locate(position);
    bufferQueue.put(downloads.submit(() -> bytesOf(location)));   // blocks when full
    position += location.bytesLeftInSegment();
}
```

`QUEUE_FULL_SLEEP_MS` and the inner loop both go away. One subtlety to handle: a blocked
`put()` no longer notices `running` going false, so `stopWorker()` must interrupt the
worker thread (keep a reference to it) rather than relying on the flag alone. That is a
better design anyway — the current `stopWorker` sets a flag and hopes.

Note `maxAhead` must be ≥ 1; the constructor already guarantees that
(`parallelDownloads == 1 ? 1 : MAX_AHEAD`).

**Verify**

Existing stream tests must still pass. Add one that fills the queue and asserts the worker
does not spin (e.g. assert the fetch count stops at `maxAhead + parallelDownloads`).

---

### C2 — Half-synchronized stream

- [ ] **Fix** · **S** · `streams/VirtualFileInputStream.java:43-54,187-194`

**What happens**

```java
private BlockingQueue<Future<byte[]>> bufferQueue = new LinkedBlockingQueue<>();
private AtomicBoolean endOfSegments = new AtomicBoolean(false);
private AtomicBoolean running = new AtomicBoolean(false);
private long position;
private byte[] currentBytes;
private int cursor;
```

`seek()`/`startWorkerAt()` reassign the first three together; `read()` reads all of them.
Neither is synchronized or volatile. Meanwhile:

```java
@Override public synchronized void mark(int readLimit) { markPosition = position; }
@Override public synchronized void reset() { seek(markPosition); }
```

**Why it matters**

The `synchronized` on `mark`/`reset` advertises thread safety the class does not have —
worse than no synchronization, because a reader will trust it. The `AtomicBoolean`s do the
same: they make the fields look concurrency-aware while the *reference swap* between them
is what actually needs to be atomic. Three separate mutable references that must change as
a unit is the shape that produces "sometimes it reads bytes from the previous seek".

**Fix**

Either:

*(a) Declare the truth* — one reader thread only:

```java
/** Not thread safe. One reader thread uses one stream. The worker thread only writes to the queue. */
```

and drop the two `synchronized` keywords.

*(b) Make the swap atomic* — bundle the per-worker state into one immutable object:

```java
private record Session(BlockingQueue<Future<byte[]>> queue, AtomicBoolean endOfSegments,
                       AtomicBoolean running) {}

private volatile Session session;
```

`startWorkerAt` publishes a whole new `Session`; `read()` reads it once into a local. One
volatile write, one volatile read, no torn state. This pairs naturally with the
`SegmentSource` refactor in [B4](#b4--two-stream-classes-sharing-a-duplicated-skeleton).

---

### C3 — Tuning constants live in three places, two of them fiction

- [ ] **Fix** · **S** · `streams/VirtualFileInputStream.java:56-57`, `workers/DownloadSegmentsWorker.java:35-48`, `src/main/resources/application.properties:27-29`, `application-local.properties:21-23`

**What happens**

| Value | Declared in | Actually used? |
|---|---|---|
| `MAX_RETRIES = 3` | `VirtualFileInputStream:56` | yes, passed to the worker |
| `MAX_RETRIES = 3` | `DownloadSegmentsWorker:47` | **no** — shadowed by the ctor parameter |
| `PARALLEL_DOWNLOADS = 8` | `VirtualFileInputStream:57` | yes |
| `PARALLEL_DOWNLOADS = 8` | `DownloadSegmentsWorker:45` | only in a javadoc `{@value}` |
| `MAX_AHEAD = 16` | `DownloadSegmentsWorker:35` | yes |
| `download.worker.min-buffer-size=4` | both `.properties` files | **read by nothing** |
| `download.worker.max-retries=3` | both `.properties` files | **read by nothing** |
| `download.worker.buffer-full-sleep-ms=10` | both `.properties` files | **read by nothing** |

Verified: `grep -rn "download.worker" src/main --include=*.java` returns zero hits. The
properties are a leftover from an earlier design, and `AGENTS.md:24` still documents
`download.worker.min-buffer-size` as the live buffering knob — so the documentation
describes a setting that does nothing.

**Why it matters**

Someone will eventually tune `download.worker.min-buffer-size` to fix a stuttering
playback, observe no change, and conclude the buffering is broken.

**Fix**

Pick one home. Given the pool size is already a property (`usenet.pool-size`), be
consistent and make these properties too:

```java
@Component
public class DownloadSettings {
    private final int parallelDownloads;   // usenet.download.parallel   (default 8)
    private final int segmentsAhead;       // usenet.download.ahead      (default 16)
    private final int maxRetries;          // usenet.download.retries    (default 3)
}
```

Delete the `download.worker.*` keys from both properties files, delete the duplicated
constants from `DownloadSegmentsWorker`, and fix `AGENTS.md:24`.

If you would rather keep them as constants, that is fine too — but then delete the
properties, all six lines, so nothing suggests they are configurable.

---

## D. Code quality

---

### D1 — `UsenetDownloadService` is three services in one

- [ ] **Fix** · **M** · `service/UsenetDownloadService.java`

The file holds three unrelated jobs: bulk file download to disk (`downloadFile`), single
segment fetch (`downloadAndDecodeSegment` ×2), and size probing (`populateNzbFileSizes` ×2).
The first two have been superseded by `SegmentFetcher` + `UsenetConnectionPool`; only the
third is still on a live path (via [B2](#b2--a-parser-that-talks-to-the-internet)).

Specific problems, all in this one file:

- **`System.out` mixed with log4j** — lines 49, 95, 100 print to stdout while the class
  holds a `Logger`. In a container, half your diagnostics go somewhere different from the
  other half.
- **A pointless round-trip** — lines 39–46 connect, select a newsgroup, and immediately
  disconnect in `finally`. The result is discarded; the only effect is ~200 ms and one
  connection slot.
- **Temp-file leaks** — line 81 deletes only the files already collected into `tempFiles`;
  everything still in flight stays in `%TEMP%\nzb-segments` forever. `File.delete()`'s
  return value is ignored at both call sites (81, 87). Use `Files.delete` + try/finally, or
  better, `Files.createTempFile` with a shutdown-registered cleanup — or skip disk entirely,
  since `SegmentFetcher` already returns byte arrays.
- **A copy of a copy** — lines 161–163:
  ```java
  ByteArrayOutputStream bos = new ByteArrayOutputStream();
  bos.write(decoded);
  return bos.toByteArray();      // == decoded, via two full copies of a multi-MB array
  ```
  `return decoded;`
- **Exception laundering** — `catch (Exception e) { throw new RuntimeException(e); }` at
  line 62 and again at line 70.
- **Overload confusion** — two methods named `downloadAndDecodeSegment` with different
  return types (`TempSegment` vs `byte[]`), different arities, and different lifetimes.
- **`TempSegment`** (line 296) is a mutable-free 2-field carrier written as a class with a
  constructor — it wants to be a `record`, in a project already using records elsewhere.

**Fix**

Move `populateNzbFileSizes` + `readStart` + `YencStart` into a new `SegmentSizeResolver`
(see [B2](#b2--a-parser-that-talks-to-the-internet)), then **delete the rest of the file**
along with its only remaining caller, `NzbController.download`
([D2](#d2--nzbcontroller-has-three-copies-of-one-endpoint-and-a-debug-endpoint)).
`DownloadResult` and `model/DownloadResult.java` go with it.

---

### D2 — `NzbController` has three copies of one endpoint, and a debug endpoint

- [ ] **Fix** · **M** · `controller/NzbController.java`

`uploadNzbFile` (line 42), `uploadNzbFileRaw` (line 105) and `test` (line 155) are ~60
lines each and differ only in how they obtain the `InputStream` and whether they call
`processNzbFile` or `processNzbFileWithoutSaving`. Each ends with the same three or four
`catch` blocks building the same `HashMap` response.

`download` (line 204) is worse — it is debug scaffolding wearing a `@PostMapping`:

```java
int i=1;
for (NzbFile nzbFile : nzb.getFiles()) {
    if (i > 1) filename = "sample.part" + i + ".rar";
    else       filename = "sample.part1.rar";
    i++;
    File downloadedNzbFile = new File("downloads/" + filename);
```

Hardcoded output names, a relative path that depends on the working directory, no
size limit, and the parameter `filename` reused as a loop variable.

**Fix**

1. Delete `download` and `test`. They are reachable from the network and do things no user
   asked for.
2. Collapse the two uploads into one method that takes the bytes:
   ```java
   private ResponseEntity<Map<String, Object>> process(InputStream in, String filename) { ... }
   ```
3. Move the error mapping to a `@ControllerAdvice`:
   ```java
   @RestControllerAdvice
   class ApiErrors {
       @ExceptionHandler(NzbParseException.class)
       ResponseEntity<ApiResponse> parse(NzbParseException e) { ... 422 ... }
       @ExceptionHandler(Exception.class)
       ResponseEntity<ApiResponse> unexpected(Exception e) { ... 500 ... }
   }
   ```
   The four copies of `catch (Exception e) → log → put → 500` become zero.
4. Replace the `Map<String, Object>` responses with a `record ApiResponse(boolean success, String message, String filename, Integer filesCount)`.

---

### D3 — Field injection where the rest of the code uses constructors

- [ ] **Fix** · **S** · `service/NzbProcessingService.java:29-36`, `controller/NzbController.java:29-33`, `controller/UiController.java:16-17`, `controller/VirtualFileController.java:28-32`

Four classes use `@Autowired` on private fields; `VirtualResourceService`,
`UsenetDownloadService`, `SegmentFetcher` and `UsenetConnectionPool` use constructors.
Pick one, and pick the one that works: constructor injection makes the dependency list
visible, allows `final`, and makes the class constructible in a test without a container.

Note the correlation — the four field-injected classes are exactly the four with no tests.

---

### D4 — `NzbProcessingService.processNzbFile` does four jobs and lies about failure

- [ ] **Fix** · **M** · `service/NzbProcessingService.java:46-111`

One 60-line method: parse, transform, persist virtual files, create the WebDAV root, create
a per-NZB folder, create a resource per file. Wrapped in:

```java
} catch (Exception e) {
    log.error("Failed to process NZB file: {}", filename, e);
    throw new NzbParseException("Failed to process NZB file: " + filename, e);
}
```

So a Postgres connection failure, a Usenet timeout, and a malformed XML document all reach
the user as the same message, and `NzbController` maps `NzbParseException` to **422
Unprocessable Entity** — telling the client their file was bad when the database was down.

**Also:** the method is `@Transactional`, and the transform step inside it does network I/O
— one Tika parse per post, each pulling segments over NNTP. A 50-file NZB holds a database
transaction open for the entire Usenet conversation, which can be minutes. Under a few
concurrent uploads that will exhaust the JDBC pool while doing nothing but waiting on
sockets.

**Fix**

- Split into named private steps, or better, let the service orchestrate three collaborators
  (parser, resolver, transformer) and one persistence step — see
  [B2](#b2--a-parser-that-talks-to-the-internet).
- **Move the network work out of the transaction.** Parse + resolve + transform with no
  transaction; open a short `@Transactional` method that only saves. This is the change with
  the biggest operational payoff in this section.
- Catch narrowly. Let `DataAccessException` propagate as a 500; keep `NzbParseException`
  for genuine parse failures.
- `processNzbFileWithoutSaving` (line 114) exists only for the `test` endpoint being deleted
  in [D2](#d2--nzbcontroller-has-three-copies-of-one-endpoint-and-a-debug-endpoint) — delete it too.

---

### D5 — Code that computes and then discards, or contradicts itself

- [ ] **Fix** · **S**

- **`webdav/VirtualFolderResource.java:109-119`** — `getContentLength()` loops over children
  summing sizes into `size`, then `return null`. Either return the sum or delete the loop;
  right now it reads as a bug that someone half-fixed.
  ```java
  long size = 0L;
  for (Resource r : children) { ... size += l; }
  return null;               // ← the loop was for nothing
  ```
- **`streams/VirtualFileRangeStream.java:23,88,107`** — the field `location` is assigned in
  `load()` and cleared in `seek()`, and never read. Delete it (`load()` already uses a local).
- **`webdav/VirtualFileResource.java:116-123`** — `getContentType(String accept)` hardcodes
  `.mp4`/`.mkv`/octet-stream, while the object already carries Tika's answer in
  `vf.getContentType()` *and* stores it in the `getcontenttype` field (line 38) that the
  `@BeanProperty` getter returns. So PROPFIND and GET can report different content types for
  the same resource. Return `vf.getContentType()` with the extension check as fallback only.
- **`model/VirtualFile.java:147-153`** — both `filename()` and `getFilename()` return the
  same field. JPA needs the bean form; pick `getFilename()` and update the ~10 call sites,
  or keep `filename()` as the domain vocabulary and mark the other `@Deprecated`. Do not
  keep both silently.
- **`webdav/AbstractResource.java:44-72`** — ~30 lines of commented-out authentication.
  Delete it; git has it. (Same for `MultiPartDecoder:29` once
  [A5](#a5--segment-integrity-is-never-verified) is resolved.)

---

### D6 — Dead code

- [ ] **Delete** · **S**

Verified by grep as having exactly one occurrence — the definition itself:

| Symbol | File |
|---|---|
| `SingePartDecoder` (also misspelled) | `decoder/SingePartDecoder.java` |
| `NzbToStringTransformer` | `transformers/NzbToStringTransformer.java` |
| `NzbFile.getSegmentAtPosition` | `model/NzbFile.java:68` |
| `NzbFile.getTotalBytes` | `model/NzbFile.java:56` |
| `VirtualResourceRepository.findByPathWithChildren` | `repository/VirtualResourceRepository.java:18` |
| `VirtualResourceRepository.findAllFileResources` | `repository/VirtualResourceRepository.java:30` |
| `VirtualResourceFactory.addUser` | `webdav/VirtualResourceFactory.java:50` |
| `VirtualFileInputStream.withoutRetry` | `streams/VirtualFileInputStream.java:98` |

Also: the whole `org.example` package (`JavaUnrar`, `MagicBytesExtractor`, `MetadataMain`,
`RarHeaderScan`, `RarMain` — 493 lines of scratch harnesses) except `Main`, which is the
Spring entry point and should move to `org.nzbstreamer` so that `scanBasePackages` becomes
unnecessary. And `src/test/java/org/decoder/YencDecoderTest.java`, which is one line long
and empty — replace it with the real decoder tests from
[A5](#a5--segment-integrity-is-never-verified) or delete it.

`NzbFile.getSegmentAtPosition` and `getSegmentSize` are particularly worth removing: they
are the *old* position-mapping logic, superseded by `VirtualFile.locate()`. Leaving both in
the tree invites someone to use the wrong one.

---

### D7 — Small correctness and hygiene items

- [ ] **Fix** · **S** · `utils/NzbUtils.java`, `parser/JaxbNzbParser.java`

- **`NzbUtils.isMediaType(null)`** throws NPE. It is called with
  `metadata.get(Metadata.CONTENT_TYPE)` guarded at `TikaNzbFileTransformer:120`, but also
  with `tika.detect(openName)` at line 175 — guard inside the method instead.
- **`NzbUtils.sanitizeFileName`** silently returns the raw subject when there is no quoted
  section, so an unusual subject line becomes a filename unchanged — including path
  separators. Given these names end up in `VirtualResource.path` and in URLs, reject or
  sanitise the fallback path too.
- **`NzbUtils`** has no private constructor; it is a static utility holder that can be
  instantiated.
- **`XMLReaderFactory`** (`JaxbNzbParser:115`) has been deprecated since Java 9. Use
  `SAXParserFactory` with `setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)`.
- **DTD is still allowed.** You disabled external entities (good) but not the DOCTYPE
  declaration, so an internal-subset entity-expansion bomb still parses. Add:
  ```java
  reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
  ```
- **`JaxbNzbParser:118`** — two statements on one line (`setFeature(...); return reader;`),
  which is how the missing feature above is easy to overlook.
- **`JaxbNzbParser.stripBom`** calls `in.readAllBytes()`, so the whole NZB is held twice in
  memory. Fine at the current 2 MB multipart limit; note it if that limit rises
  ([E3](#e3--multipart-limit-is-smaller-than-real-nzb-files)).

---

### D8 — The yEnc-through-`Reader` dependency is undocumented

- [ ] **Document or fix** · **S** · `service/NNTPClientFactory.java`, `decoder/AbstractYencDecoder.java:42`

You decode binary yEnc payloads out of a character `Reader`:

```java
int ch = line.charAt(i) & 0xFF;
```

This is byte-transparent **only** because Apache commons-net's `NNTP` uses
`StandardCharsets.ISO_8859_1` as its `DEFAULT_ENCODING` (confirmed in
`commons-net-3.12.0.jar`), and ISO-8859-1 maps all 256 byte values one-to-one. Nothing in
your code states this. A single `client.setCharset(...)` anywhere, a commons-net upgrade
that changes the default, or a swap to a different NNTP library, and every byte ≥ 0x80
silently becomes `U+FFFD` — corrupting downloads with no error anywhere.

**Fix**

Pin it explicitly, with the reason:

```java
NNTPClient client = new NNTPClient();
// yEnc payloads are binary. ISO-8859-1 is the one charset that maps all 256 byte values
// one to one, so the Reader gives back exactly the bytes the server sent. Do not change
// this without switching the decoder to read raw bytes.
client.setCharset(StandardCharsets.ISO_8859_1);
client.connect(server, port);
```

The stronger version is to stop using `Reader` for binary at all and decode from the raw
socket stream, but that means leaving `retrieveArticle`'s convenience behind — worth doing
only if you hit a real problem. The comment costs nothing and prevents the silent version
of the failure.

---

## E. Configuration

---

### E1 — `application.properties` forces the local profile

- [ ] **Fix** · **S** · `src/main/resources/application.properties:1`

```properties
spring.profiles.active=local
```

The template properties file — the one that ships in the jar with `YOUR_USENET_SERVER`
placeholders — hardcodes the local dev profile. Anyone running the fat jar without
`-Dspring.profiles.active=…` gets `local` whether they have that file or not. Delete the
line; select the profile on the command line, as `AGENTS.md:45` already describes.

---

### E2 — `ddl-auto=update` in the shipped config

- [ ] **Decide** · **S** · `src/main/resources/application.properties:22`

`spring.jpa.hibernate.ddl-auto=update` lets Hibernate mutate the schema at boot. That is
fine for a solo dev database and dangerous anywhere else — it never drops or renames
correctly, so the schema silently accumulates drift that no migration file records. If this
project is going to outlive the experiment, adopt Flyway (one dependency, one
`V1__init.sql`) and set `ddl-auto=validate`. If not, leave it and note the decision here.

---

### E3 — Multipart limit is smaller than real NZB files

- [ ] **Fix** · **S** · `src/main/resources/application.properties:23-24`

```properties
spring.servlet.multipart.max-file-size=2MB
spring.servlet.multipart.max-request-size=2MB
```

An NZB for a large multi-volume release routinely exceeds 2 MB (they are verbose XML, one
`<segment>` element per ~640 KB of content). Users will hit a container-level 500 with no
useful message. Raise to 32 MB and add an `@ExceptionHandler(MaxUploadSizeExceededException.class)`
returning 413 with a clear message.

---

### E4 — `nntp.properties` is dead

- [ ] **Delete** · **S** · `src/main/resources/nntp.properties`

`NNTPClientFactory` reads `usenet.*` via `@Value`; nothing loads `nntp.properties`.
It duplicates the same four settings under different names, and `AGENTS.md:31` still
documents it as the live credential source that "bypasses Spring property binding
entirely". Delete the file, fix the doc.

Also note `NNTPClientFactory` uses `@Value` on private fields; convert to constructor
parameters for the same reasons as [D3](#d3--field-injection-where-the-rest-of-the-code-uses-constructors).

---

## F. Repository, tests and docs

---

### F1 — Committed junk

- [ ] **Clean** · **S**

- **`repository/`** — roughly 200 tracked binary files: Apache Jackrabbit's Derby database
  (`version/db/seg0/c*.dat`), Lucene index segments, and lock files, from an experiment the
  code no longer uses. Nothing in `src/` references Jackrabbit. Remove from tracking
  (`git rm -r --cached repository/`) and add to `.gitignore`.
- **`dependency-reduced-pom.xml`** — generated by the maven-shade-plugin, which is not even
  in the current `pom.xml`. Delete and ignore.
- **`.env`** — present on disk, correctly ignored, but confirm it holds nothing you would
  mind losing, since it is invisible to git history.
- **`logs/`, `downloads/`, `target/`** are correctly ignored already.

---

### F2 — The test suite covers the safe parts

- [ ] **Extend** · **L**

Current state: 7 test classes, ~800 lines, all in `rar`, `model` and `streams`.

| Area | Risk | Tests |
|---|---|---|
| `rar` header parsing | medium | **good** — fixtures, negative cases, laziness, multi-volume |
| `VirtualFile.locate` | high | **good** |
| `VirtualFileInputStream` | high | partial — one test is a race ([A6](#a6--a-test-that-passes-by-winning-a-race)) |
| yEnc decoder | **high** | none (`YencDecoderTest` is an empty file) |
| `UsenetConnectionPool` borrow/release/discard | **high** | none |
| Range handling | **high** | none — and it has a bug ([A3](#a3--range-responses-can-overrun-content-length)) |
| `TikaNzbFileTransformer.joinVolumes` | **high** | none |
| Controllers | medium | none |

The two areas with confirmed defects in this review are the two with no tests. Suggested
order, cheapest first:

1. `ByteRanges.copy` from [B5](#b5--range-streaming-logic-is-duplicated) — pure function,
   4 cases, catches [A3](#a3--range-responses-can-overrun-content-length).
2. Decoder fixtures — good article, corrupt article, nfo article
   ([A5](#a5--segment-integrity-is-never-verified)).
3. `UsenetConnectionPool` with a fake `NNTPClientFactory` — assert permits are released on
   every path (`release`, `discard`, `releaseAfterDrain` success **and** failure), and that
   a disconnected client is replaced rather than reused. Permit leaks here deadlock the
   whole application, and there are five exit paths.
4. `joinVolumes` with synthetic `RarFileEntry` lists — split-before/split-after chains,
   a missing middle volume, an entry that spans three volumes.

`SegmentFetcher`-as-interface ([B1](#b1--service-locator-hides-dependencies)) is a
prerequisite for 3 and makes 4 much easier.

---

### F3 — `AGENTS.md` is stale in at least six places

- [ ] **Rewrite after the fixes** · **S** · `AGENTS.md`

| Line | Claim | Reality |
|---|---|---|
| 23, 31 | `NNTPClientFactory` reads `nntp.properties`, bypassing Spring | it uses `@Value` on `usenet.*` ([E4](#e4--nntpproperties-is-dead)) |
| 37 | WebDAV credentials hardcoded as `usera`/`password` | no such code; auth is a stub ([A1](#a1--webdav-authentication-does-not-exist)) |
| 53 | RAR enumeration is commented out, inner files not surfaced | `TikaNzbFileTransformer.joinVolumes` does exactly that |
| 15–17 | `NzbTransformerFactory` picks a transformer by Tika type | it returns one transformer, ignoring its argument ([B3](#b3--a-factory-that-decides-nothing)) |
| 22, 24 | single-thread `ExecutorService`, `download.worker.min-buffer-size` (default 4) | 8-thread pool, `MAX_AHEAD=16` constant, property unused ([C3](#c3--tuning-constants-live-in-three-places-two-of-them-fiction)) |
| 25 | decodes with `MultiPartDecoder` or `SingePartDecoder` | `SingePartDecoder` is unreferenced ([D6](#d6--dead-code)) |
| 54 | `VirtualFileInputStream.skip()` has a TODO | `skip` now delegates to `seek`; the TODO is gone |

A document that must be re-verified before it can be trusted has negative value — it costs
a reader time and then misleads them anyway. Rewrite it once the structural items are done,
and keep it to things that are hard to learn from the code (the *why* of the pool, the
volume-joining rule, the Milton double-instantiation) rather than restating the package
list.

---

### F4 — Comment style

- [ ] **Optional** · `streams/`, `workers/`, `service/`, `model/`

The newer javadoc consistently explains *why*, which is the hard part and you are doing it —
`UsenetConnectionPool`'s "200 ms handshake vs 300 ms transfer" and
`DownloadSegmentsWorker`'s explanation of why it uses a pool rather than a thread per
download are exactly right, and they are the comments that will save you in six months.

The one thing working against them is the clipped, relative-clause-free phrasing:

> "The file says if a byte is at the position. The worker gives the bytes."
> "A pool of threads and not a thread for each download: the threads take the segments in
> the sequence of the file."

Several of these need a second read to resolve what "it" refers to. The content is good;
letting the sentences use ordinary connectives ("because", "which", "so that") would make
them land the first time. Purely a readability suggestion — no behaviour attached.

---

## G. What is already good

Worth recording, both so it does not get refactored away and because it is the standard the
rest of the code should be held to.

**`org.nzbstreamer.rar` is the best package here.** Small classes with one job each;
`RarBlockReader` as a genuine strategy over the RAR4/RAR5 layouts with `RarHeaderParser`
owning only the block *sequence*; a parser that reads headers and skips data because the
underlying stream is expensive, and a `RarLazinessTest` that asserts that cost rather than
just the result. `MAX_BLOCKS` and the "does not advance" check show someone thinking about
malformed input. This is how the rest should look.

**`VirtualFile.locate()` and the `Location` record.** Turning "position in the file" into
"segment, offset in segment, bytes usable from here" is the right abstraction, and it is why
`DownloadSegmentsWorker` can say *the map told me* instead of doing arithmetic. The
`VirtualFileChunk` design — offset plus first/last segment index rather than a segment list
— is a good call for both storage and clarity.

**`UsenetConnectionPool`.** Real reasoning in the javadoc, `releaseAfterDrain` is a genuinely
clever solution to the "I only need the first 64 KB but the connection must reach the end of
the response" problem, and the `healthy`/`handedOver` flags make the exit paths explicit.
(It needs tests — [F2](#f2--the-test-suite-covers-the-safe-parts) — precisely because it is
subtle.)

**`VirtualFileRangeStream`'s prefix strategy.** Fetching 64 KB, then upgrading to the full
segment only when a read demands a byte beyond it, is the right shape for header scanning
over an expensive network.

**The commit "Download only the bytes that a parser reads"** is the kind of change that
justifies the whole architecture. Keep going in that direction.
