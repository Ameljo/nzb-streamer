# nzb-streamer

A Java exploration into streaming NZB files over WebDAV, serving media 
directly to players without downloading the full content first.

## What it does

- Implements a custom Java InputStream that presents segmented NZB 
  content as a single continuous file to media players
- Byte-level seeking — maps any requested byte offset to the exact NZB 
  segment and intra-segment byte position, without re-downloading from 
  the start
- Background segment prefetching via a dedicated thread, staying ahead 
  of playback to prevent buffering
- RAR file streaming — streams content inside RAR archives over WebDAV
- RAR5 header parsing using Kaitai Struct, a binary format description 
  language that generates parsers from a .ksy schema

## Status

Exploratory/work in progress. Single-part RAR streaming works. 
Multipart RAR seeking is the current open problem — byte offset 
calculation across part boundaries is the blocker.

## Tech

- Java
- WebDAV
- Kaitai Struct (RAR5 binary format parsing)
- Maven
