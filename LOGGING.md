# Logging — current state, conventions, and a cleanup list

Companion to `CODE_REVIEW.md`. This one is reference material rather than a defect list:
what the logging setup actually is today, what level to use for what, and the mechanical
fixes outstanding.

Verified against the tree at review time (2026-08-15).

---

## 1. What is actually running

**The logging stack is Logback, not Log4j2 — despite every class importing Log4j2.**

`mvn dependency:tree` gives:

```
ch.qos.logback:logback-classic:1.4.11          ← the implementation
ch.qos.logback:logback-core:1.4.11
org.apache.logging.log4j:log4j-to-slf4j:2.21.1 ← the bridge
  └─ org.apache.logging.log4j:log4j-api:2.21.1 ← the API your code calls
org.slf4j:jul-to-slf4j:2.0.9
org.slf4j:slf4j-api:2.0.17
```

So a call flows:

```
LogManager.getLogger(X.class)     org.apache.logging.log4j  (API only)
        ↓  log4j-to-slf4j
    slf4j-api
        ↓
    logback-classic               ← decides levels, formats, writes
```

There is **no `log4j-core`** on the classpath. That matters more than it sounds.

### Consequence: `src/main/resources/log4j2.properties` does nothing

`log4j2.properties` is read by `log4j-core`. Without `log4j-core`, nothing reads it. The
file currently declares:

```properties
rootLogger.level = trace
appender.console.layout.pattern = [%-5level] %d{...} [%t] %c{1} - %msg%n
```

None of that is in effect. The console pattern you actually see is Spring Boot's Logback
default. If you set a level in this file, nothing will happen and you will lose an hour
working out why.

- [ ] **Delete `src/main/resources/log4j2.properties`.**

### Where levels really come from

`application.properties` / `application-local.properties`, via Spring Boot's Logback
configuration:

```properties
logging.level.io.milton=DEBUG
logging.level.org.webdav=DEBUG
logging.level.org.example=DEBUG
logging.level.org.nzbstreamer.transformers=DEBUG
```

(`logging.level.org.webdav` matches no package in this project — leftover. `org.example` is
being deleted per `CODE_REVIEW.md` D6.)

### `logback-trace.xml`

This one is fine and its header comment is accurate: Spring Boot reads `logback.xml` or
`logback-spring.xml`, **not** `logback-trace.xml`, so it applies only when you pass
`-Dlogback.configurationFile=logback-trace.xml`. It is a deliberate opt-in profile for the
standalone example programs. Keep it; it does what it says.

---

## 2. Optional simplification: drop the Log4j2 API

Every class does:

```java
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
private static final Logger log = LogManager.getLogger(Foo.class);
```

…and every call is then bridged to SLF4J anyway. Since Logback is what actually runs,
using SLF4J directly removes an API and a bridge from the mental model, and makes the
config story unambiguous (one file, one mechanism):

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
private static final Logger log = LoggerFactory.getLogger(Foo.class);
```

The call sites are unchanged — SLF4J uses the same `{}` placeholder syntax. It is a
mechanical import swap across ~20 files, and `log4j-to-slf4j` can then be excluded from the
starter.

Not urgent. Worth doing on the day someone is confused by the two config files.

- [ ] Optional: swap `LogManager`/`Logger` → `LoggerFactory`/`Logger`, drop `log4j-to-slf4j`.

---

## 3. Level policy

| Level | Use for | Frequency you should expect |
|---|---|---|
| **error** | An operation failed and the user sees it: a download that failed all retries, a stream that aborted mid-file | rare; every one deserves investigation |
| **warn** | Something recovered, or a measurement crossed a threshold: a retry, a size mismatch, an archive entry skipped, a pool wait over 1 s | occasional; a burst means something is wrong |
| **info** | One line per user-visible action: NZB processed, file registered, pool opened at startup | a handful per request |
| **debug** | Per-operation detail: per segment, per stream open/close, per WebDAV resource resolved, per-operation timings | hundreds per request — off by default |
| **trace** | Inside a loop: per read, per block header, header dumps | thousands per request — for a bug hunt only |

The test for `info` vs `debug`: *would you want this line in production for every user, every
time?* Segment-level detail is no; "processed X.nzb, 4 files, 12.3 GB" is yes.

### Applying it to this codebase

Correct today, keep as is:

- `UsenetConnectionPool:57` — `log.info("connection pool of {} connections", size)` at startup
- `TikaNzbFileTransformer:103` — one info per post with format, entry count, bytes read
- `DownloadSegmentsWorker:158` — `log.warn("Retry {}/{} for {} ...")`
- `DownloadSegmentsWorker:144` — `log.warn` on a segment size that disagrees with the map
- `SegmentFetcher:69` — per-segment timing at debug

Wrong today:

| Site | Now | Should be | Why |
|---|---|---|---|
| `VirtualFileResource:111` | `info` | `debug` | Milton calls `getContentLength()` per request, per resource |
| `VirtualFileResource:121` | `info` | `debug` | same, `getContentType()` |
| `VirtualFileResource:127` | `info` | `debug` | `"sendContent called"` with no context |
| `VirtualFileResource:158` | `debug` | `trace` | inside the 64 KB copy loop — see §5 |
| `AbstractResource:93` | `debug` | `trace` | `"authorise"`, no context, every request |

---

## 4. Performance and timing logs

This project is a latency problem wearing a filesystem, so timings are load-bearing. Three
tiers, plus one rule that matters more than the tiers.

### Tier 1 — per-operation timing → `debug`

One line per segment, per connection, per article. Already the convention here, and the
format is good because it *decomposes* the total:

```java
// SegmentFetcher:69
log.debug("segment {}: {} bytes in {} ms = group {} ms + transfer {} ms",
        messageId, bytes.length, totalMs, groupMs, transferMs);
```

`total = a + b + c` in a single line is the right shape — it answers "where did the time
go" without correlating across lines. Keep this pattern for new instrumentation.

Existing tier-1 sites: `SegmentFetcher:69` and `:123`, `DownloadSegmentsWorker:131`,
`UsenetConnectionPool:133` (drain), `UsenetDownloadService:145` and `:263`.

### Tier 2 — summary per user action → `info`

One line when a whole operation finishes, with the numbers that describe the *shape* of the
work, not just elapsed time:

```java
// JaxbNzbParser:47 — good
log.info("NZB of {} posts read in {} ms", nzb.getFiles().size(), elapsedMs);

// TikaNzbFileTransformer:103 — the best log line in the project
log.info("{}: {} archive, {} entries, headers found with {} bytes in {} ms", ...);
```

That second one is worth calling out: `bytesRead` is the metric this entire project exists
to minimise. "We identified a 40 GB archive by downloading 300 KB" is the value
proposition, measured, at info, in production. Add the equivalent when a stream closes:

```java
log.info("{}: closed at {} of {} after {} segments, {} MB in {} s",
        file.filename(), position, file.getSize(), segmentsFetched, mb, seconds);
```

Missing today — `VirtualFileInputStream.close()` logs only the position, at debug.

### Tier 3 — inside a loop → `trace`

Per read, per block, per line. Never `debug`: a debug level that is unusable in practice
because one class floods it is the same as having no debug level.

### The rule that matters: log the outlier at `warn`

A timing at `debug` is invisible unless someone already suspected a problem. The point of
instrumenting is to be told *before* you suspect. So keep the per-operation line at debug
**and** add a threshold check that fires at warn.

The clearest example in this codebase — `UsenetConnectionPool:88-94`:

```java
permits.acquire();
long waitedMs = (System.nanoTime() - startedAt) / 1_000_000;
if (waitedMs > 0) {
    log.debug("waited {} ms for a connection, {} free of {}", waitedMs,
            permits.availablePermits(), size);
}
```

Pool exhaustion is the failure mode that stalls this whole application (see
`CODE_REVIEW.md` A2), and today it is reported at debug, off by default. Make it:

```java
private static final long SLOW_BORROW_MS = 1_000;

if (waitedMs > SLOW_BORROW_MS) {
    log.warn("waited {} ms for a connection, {} free of {} — the pool is the bottleneck",
            waitedMs, permits.availablePermits(), size);
} else if (waitedMs > 0) {
    log.debug("waited {} ms for a connection, {} free of {}", ...);
}
```

Worth the same treatment:

| Measurement | Threshold suggestion | Site |
|---|---|---|
| Connection wait | > 1 s | `UsenetConnectionPool:91` |
| Segment fetch | > 5 s | `SegmentFetcher:69` |
| Reader starved (queue empty when `read()` blocks) | any occurrence | `VirtualFileInputStream:116` |
| Header scan bytes | > 5 MB to find headers | `TikaNzbFileTransformer:103` |

The starvation one is the most valuable and does not exist yet: if `bufferQueue.take()`
blocks, the player is waiting on the network and playback is about to stutter. Time that
`take()` and warn when it exceeds ~200 ms. That single line tells you the difference
between "the network is slow" and "my prefetch depth is too small", which no other log
currently distinguishes.

### Cost of the measurement itself

`System.nanoTime()` is ~20–25 ns — negligible next to a 300 ms network call, so the
unconditional timing in `SegmentFetcher` is fine as is. The expensive part is never the
clock, it is building the message; see §5.

Only guard with `if (log.isDebugEnabled())` when producing the *arguments* is costly —
e.g. formatting a byte array, walking a collection, or `request.getHeaders()` at
`VirtualFileResource:132`.

### When timings outgrow logs

Logs answer "what happened in this one request". They do not answer "what is the p95
segment latency this week", because grepping timings out of text and aggregating them is a
chore you will do twice and then stop doing.

`spring-boot-starter-actuator` + Micrometer is a small dependency and gives you
`Timer`/`Counter`/`Gauge` with percentiles for free:

```java
Timer.builder("usenet.segment.fetch").register(registry).record(() -> fetch(...));
Gauge.builder("usenet.pool.free", permits, Semaphore::availablePermits).register(registry);
```

Not needed now. Reach for it when you find yourself pasting log lines into a spreadsheet.

---

## 5. Parameterised logging (the actual hygiene problem)

**Rule: never concatenate in a log call.**

```java
log.debug("sendContent: read " + read + " bytes");   // string built ALWAYS
log.trace("sendContent: read {} bytes", read);       // string built only if enabled
```

With `+`, the `StringBuilder` runs before the method is entered, so the work happens at
every level, including OFF. The level check inside the logger only saves the *write*.

`VirtualFileResource:158` is the one that stings — it sits inside the 64 KB copy loop, so
a 10 GB playback allocates ~160,000 strings that are then discarded regardless of
configuration.

### Sites to convert (14)

| File | Lines |
|---|---|
| `webdav/VirtualFileResource.java` | 111, 121, 132, 158, 164 |
| `webdav/AbstractResource.java` | 95 (+ 49, 52, 70 inside commented blocks — delete those) |
| `service/VirtualResourceService.java` | 37, 52, 57 |
| `service/UsenetDownloadService.java` | 144 |
| `webdav/VirtualFolderResource.java` | 94 |

Two of these need more than a mechanical fix:

- `VirtualResourceService:57` —
  `log.debug("_found: " + r + " for url: " + url + " (adjusted: " + url + ") and path: " + url)`
  prints the same variable three times under three different labels, and `r` is always
  `null` on that branch. It is debugging scratch: replace with
  `log.debug("no resource at {}", url)`.
- `VirtualFileResource:164` — `log.error("Error sending content: " + e.getMessage(), e)`
  puts the message in the text *and* passes the exception. Just
  `log.error("cannot send {}", vf.filename(), e)` — the stack trace already carries the
  message, and duplicating it makes the log harder to scan.

### Exceptions go in the last argument, never in the text

```java
log.error("cannot download segment {}", messageId, e);   // stack trace printed
log.error("cannot download segment " + messageId + ": " + e.getMessage());  // no stack trace
```

Both SLF4J and the Log4j2 API treat a trailing `Throwable` specially even when there is no
`{}` for it. `NzbProcessingService:108` and `DownloadSegmentsWorker` already do this
correctly.

---

## 6. `System.out` / `System.err`

Console printing splits your diagnostics across two destinations with different formats and
no levels — in a container, one of them usually vanishes.

Production code (4 sites, all in `UsenetDownloadService`):

| Line | Current | Action |
|---|---|---|
| 37 | `System.out.println("Downloading (async): " + fileName)` | `log.info("downloading {}", fileName)` |
| 83 | `System.out.printf("  Segment %d/%d: %s (async)%n", ...)` | `log.debug("segment {}/{}: {}", ...)` |
| 88 | `System.err.println(client.getReplyString())` | **delete** |
| 129 | `System.err.println(client.getReplyString())` | **delete** |

The two `System.err` calls print the server's reply immediately before throwing an
exception that discards it. Once the reply lives in the exception (`UsenetException`,
`CODE_REVIEW.md`), printing it separately produces an orphan line with no stack trace
attached. Delete rather than convert.

All four are inside code slated for deletion in `CODE_REVIEW.md` D1 — if you do that first,
this section resolves itself.

**`org.example` is different.** `JavaUnrar`, `MagicBytesExtractor` and `MetadataMain` are
`main()` programs whose output *is* the product. `System.out` is correct in a CLI. Those
files are deleted in D6, not converted.

---

## 7. Correlation: which stream is this line about?

With several streams downloading at once, interleaved lines are unreadable without a key.
The convention here — filename as the first placeholder — mostly works:

```java
log.debug("{}: segment {} ready in {} ms, ...", file.filename(), ...);
```

You also have a second, cleverer one at `VirtualFileInputStream:88`: `callerClass()` walks
the stack to record *who* opened the stream (transformer, WebDAV, controller) and names the
worker thread `worker-<source>`, so the thread name in the log pattern identifies the
pipeline. Keep that — it is doing real work.

If interleaving gets bad, SLF4J's MDC is the standard next step:

```java
MDC.put("file", file.filename());
try { ... } finally { MDC.remove("file"); }
```

…with `%X{file}` in the pattern. Then every line is tagged without a per-call argument.
Caveat worth knowing before you rely on it: MDC is thread-local, so it does **not**
propagate into `DownloadSegmentsWorker`'s executor tasks — you have to copy the context map
into the submitted task explicitly.

---

## 8. Recipes

Turn on the download path only:

```properties
logging.level.org.nzbstreamer.service.SegmentFetcher=DEBUG
logging.level.org.nzbstreamer.workers.DownloadSegmentsWorker=DEBUG
logging.level.org.nzbstreamer.service.UsenetConnectionPool=DEBUG
```

Everything in the project:

```properties
logging.level.org.nzbstreamer=DEBUG
```

Standalone example programs (not Spring):

```
-Dlogback.configurationFile=logback-trace.xml
```

Show the thread name — essential here, since work spans a reader thread, a worker thread
and a download pool:

```properties
logging.pattern.console=%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} -- %msg%n
```

Quiet Milton, which is verbose at DEBUG and currently enabled in `application.properties`:

```properties
logging.level.io.milton=INFO
```

---

## 9. Checklist

- [ ] Delete `src/main/resources/log4j2.properties` (inert — no `log4j-core`)
- [ ] Remove `logging.level.org.webdav` from `application.properties` (matches no package)
- [ ] Set `logging.level.io.milton=INFO` unless actively debugging WebDAV
- [ ] Convert the 4 `System.out`/`System.err` sites in `UsenetDownloadService` (2 become logs, 2 are deleted)
- [ ] Convert 14 concatenating calls to `{}` placeholders
- [ ] Fix `VirtualResourceService:57` (prints `url` three times, `r` is always null)
- [ ] Drop `VirtualFileResource:111,121,127` from info to debug
- [ ] Drop `VirtualFileResource:158` and `AbstractResource:95` to trace
- [ ] Add threshold `warn` for pool wait > 1 s (`UsenetConnectionPool:91`)
- [ ] Add threshold `warn` for reader starvation at `VirtualFileInputStream:116`
- [ ] Add an info summary line when a stream closes (bytes, segments, duration)
- [ ] Optional: swap the Log4j2 API for SLF4J and exclude `log4j-to-slf4j`
