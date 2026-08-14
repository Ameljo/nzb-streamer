# RAR test fixtures

These archives were created **once**, with a real archiver, and committed. Tests only read them —
nothing in the test suite invokes an archiver or builds RAR bytes by hand.

Created with `C:\Program Files\WinRAR\Rar.exe` (RAR 7.20) and verified independently with
`C:\Program Files\7-Zip\7z.exe` (7-Zip 25.01, `7z l -slt`).

## Payload files

Every payload starts with the ASCII marker `PAYLOAD:<name>:` followed by a repeated filler byte.
Tests use that marker to prove a reported `dataOffset` points at the real start of the file's data,
without trusting any of the parser's own header arithmetic.

| Payload | Size | Filler |
|---|---|---|
| `movie.mkv` | 8192 | `A` |
| `movie.nfo` | 1024 | `A` |
| `subs/movie.srt` | 2023 | `B` |
| `bigfile.bin` | 61440 | `A` |
| `film-café-日本.mkv` | 3030 | `C` |

## Archives

| Fixture | Command | Contents |
|---|---|---|
| `rar5-single.rar` | `rar a -m0 -ma5 rar5-single.rar movie.mkv` | one stored file, CRC `251AD45A` |
| `rar5-multi.rar` | `rar a -m0 -ma5 -r rar5-multi.rar movie.mkv movie.nfo subs` | 3 files + the `subs` directory entry |
| `rar5-vol.part1..4.rar` | `rar a -m0 -ma5 -v20k rar5-vol.rar bigfile.bin` | one 61440-byte file split over 4 volumes of 20 KB |
| `rar5-unicode.rar` | `rar a -m0 -ma5 rar5-unicode.rar "film-café-日本.mkv"` | non-ASCII UTF-8 filename |
| `rar5-comment.rar` | `rar a -m0 -ma5 -zcomment.txt rar5-comment.rar movie.nfo` | archive comment → RAR5 service block |
| `rar5-compressed.rar` | `rar a -m3 -ma5 rar5-compressed.rar movie.mkv` | method 3, 8192 → 48 bytes packed |

Negative cases (truncated headers, PAR2 magic, empty input, garbage) are derived in-test by slicing
these files; no extra fixtures are needed for them.

## No RAR4 fixtures

RAR 7.20 removed the `-ma4` switch — WinRAR 7.x can only *create* RAR5, though it still extracts
RAR4 — and no other packer is installed. The parser implements the RAR4 header layout, but until a
RAR4-capable packer is available it is **not covered by these tests**. To add coverage, produce the
archives with an older `rar` (5.x or 6.x) using the same payloads and commands with `-ma4`, drop
them in here, and enable the RAR4 tests.
