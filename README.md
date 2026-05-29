# nzb-streamer

A Java backend for streaming NZB files over WebDAV, designed to serve
media directly to players without downloading the full content first.

## What it does

- Implements a custom Java InputStream that presents segmented NZB
  content as a single continuous file to media players
- Supports byte-level seeking — calculates the exact NZB segment and
  byte offset for any seek position, without re-downloading from the start
- Background segment prefetching via a dedicated thread, preventing
  buffering during playback
- RAR file streaming support — streams content inside RAR archives
- RAR5 header parsing using Kaitai Struct (work in progress —
  multipart RAR seeking is the current blocker)
- WebDAV virtual filesystem — media players see a clean file system
  with no awareness of the underlying segmented format

## Status

Work in progress. Single-part RAR streaming works. Multipart RAR
seeking is the current challenge — the byte offset calculation across
part boundaries is the open problem.

## Tech

- Java
- WebDAV
- Kaitai Struct (binary format parsing for RAR5 headers)
- Maven