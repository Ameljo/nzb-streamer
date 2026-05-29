meta:
  id: rar5
  title: RAR Archive 5.0
  application: WinRAR / RAR
  file-extension: rar
  xref:
    mime: application/x-rar-compressed; version=5
    wikidata: Q243303
  license: MIT
  endian: le
  encoding: UTF-8

doc: |
  RAR 5.0 archive format parser.

  Based on the official RARLAB technical specification:
  https://www.rarlab.com/technote.htm

  An archive is a flat sequence of self-describing blocks. Each block begins
  with a CRC32 and a variable-length size field, which together allow a reader
  to skip unknown block types safely.  All multi-byte integers are little-endian.
  Variable-length integers (vint) use LEB128 unsigned encoding (see `vint` type).

  Typical archive layout:
    1. 8-byte magic signature
    2. Optional `block_crypt` (type 4) — only if headers are encrypted
    3. `block_main`  (type 1) — one per volume
    4. `block_file`  (type 2) — one per stored file/directory
    5. `block_file`  (type 3, service header) — optional; CMT, QO, ACL, …
    6. `block_eos`   (type 5) — end of volume marker

seq:
  - id: signature
    contents: [0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00]
    doc: |
      RAR 5.0 magic bytes (8 bytes).
      Distinguishes RAR5 from RAR4, which starts with 0x52 0x61 0x72 0x21 0x1a 0x07 0x00.

  - id: blocks
    type: block
    repeat: eos
    doc: |
      Sequence of all archive blocks. Parsing continues until end-of-stream.
      The last block in a valid archive is always a `block_eos` (type 5).

types:

  # ============================================================
  #  Variable-length integer  (LEB128 unsigned, max 8 bytes)
  # ============================================================
  vint:
    doc: |
      Unsigned variable-length integer (LEB128, little-endian base-128).

      Each byte encodes 7 data bits (bits 6..0); bit 7 is the continuation
      flag (1 = more bytes follow, 0 = this is the last byte).  Bytes are
      ordered from least-significant to most-significant.  At most 8 bytes
      may be used, giving a maximum representable value of 2^56 − 1.

      Decoding example — value 300 (0x12C):
        byte 0: 0xAC  →  continuation=1, data=0x2C
        byte 1: 0x02  →  continuation=0, data=0x02
        value  = 0x2C | (0x02 << 7) = 44 | 256 = 300

    seq:
      - id: b0
        type: u1
      - id: b1
        type: u1
        if: (b0 & 0x80) != 0
      - id: b2
        type: u1
        if: (b0 & 0x80) != 0 and (b1 & 0x80) != 0
      - id: b3
        type: u1
        if: (b0 & 0x80) != 0 and (b1 & 0x80) != 0 and (b2 & 0x80) != 0
      - id: b4
        type: u1
        if: (b0 & 0x80) != 0 and (b1 & 0x80) != 0 and (b2 & 0x80) != 0 and (b3 & 0x80) != 0
      - id: b5
        type: u1
        if: (b0 & 0x80) != 0 and (b1 & 0x80) != 0 and (b2 & 0x80) != 0 and (b3 & 0x80) != 0 and (b4 & 0x80) != 0
      - id: b6
        type: u1
        if: (b0 & 0x80) != 0 and (b1 & 0x80) != 0 and (b2 & 0x80) != 0 and (b3 & 0x80) != 0 and (b4 & 0x80) != 0 and (b5 & 0x80) != 0
      - id: b7
        type: u1
        if: (b0 & 0x80) != 0 and (b1 & 0x80) != 0 and (b2 & 0x80) != 0 and (b3 & 0x80) != 0 and (b4 & 0x80) != 0 and (b5 & 0x80) != 0 and (b6 & 0x80) != 0

    instances:
      value:
        doc: Decoded unsigned integer value.
        value: >-
          (b0 & 0x7f)
          | ((b0 & 0x80) != 0 ? (b1 & 0x7f) << 7 : 0)
          | ((b0 & 0x80) != 0 and (b1 & 0x80) != 0 ? (b2 & 0x7f) << 14 : 0)
          | ((b0 & 0x80) != 0 and (b1 & 0x80) != 0 and (b2 & 0x80) != 0 ? (b3 & 0x7f) << 21 : 0)
          | ((b0 & 0x80) != 0 and (b1 & 0x80) != 0 and (b2 & 0x80) != 0 and (b3 & 0x80) != 0 ? (b4 & 0x7f) << 28 : 0)
          | ((b0 & 0x80) != 0 and (b1 & 0x80) != 0 and (b2 & 0x80) != 0 and (b3 & 0x80) != 0 and (b4 & 0x80) != 0 ? (b5 & 0x7f) << 35 : 0)
          | ((b0 & 0x80) != 0 and (b1 & 0x80) != 0 and (b2 & 0x80) != 0 and (b3 & 0x80) != 0 and (b4 & 0x80) != 0 and (b5 & 0x80) != 0 ? (b6 & 0x7f) << 42 : 0)
          | ((b0 & 0x80) != 0 and (b1 & 0x80) != 0 and (b2 & 0x80) != 0 and (b3 & 0x80) != 0 and (b4 & 0x80) != 0 and (b5 & 0x80) != 0 and (b6 & 0x80) != 0 ? (b7 & 0x7f) << 49 : 0)

  # ============================================================
  #  Outer block wrapper  (applies to every header)
  # ============================================================
  block:
    doc: |
      Outer wrapper for every RAR5 header block.

      Field layout in the archive stream:
        header_crc32  (4 bytes)    CRC32 of all bytes from header_type through end of extra data.
        header_size   (vint)       Byte count of the span covered by the CRC (does NOT include
                                   header_crc32 itself nor the header_size vint).
        header_body   (N bytes)    Parsed via a bounded sub-stream of exactly header_size bytes.
        data          (M bytes)    Optional payload (e.g. compressed file content) that follows
                                   the header.  Present when Header flags bit 0x0002 is set;
                                   M is given by header_body.data_size_value.

    seq:
      - id: header_crc32
        type: u4
        doc: |
          CRC32 covering all header bytes from header_type through the last
          byte of the extra data area.  Used to detect header corruption.

      - id: header_size
        type: vint
        doc: |
          Size in bytes of the header data that immediately follows this field.
          Covers header_type, header_flags, the two optional vint size fields,
          the type-specific body, and the extra data area.

      - id: header_body
        size: header_size.value
        type: block_header_body
        doc: |
          Bounded parse of the header contents.  Parsed from a sub-stream of
          exactly header_size.value bytes so that unknown trailing fields in
          future block types are automatically skipped.

      - id: data
        size: header_body.data_size_value
        if: header_body.has_data
        doc: |
          Block data area.  For file blocks this contains the compressed file
          payload; for service blocks it holds service-specific binary data.
          Only present when Header flags bit 0x0002 is set.

  # ============================================================
  #  Common header prefix  (inside the header_size sub-stream)
  # ============================================================
  block_header_body:
    doc: |
      Common fields present at the start of every block header, followed by
      type-specific body data and an optional extra data area.  All fields are
      read from the sub-stream bounded by the parent block's header_size.

    seq:
      - id: header_type
        type: vint
        doc: Block type identifier — see `block_type` enum.

      - id: header_flags
        type: vint
        doc: |
          Flags shared by all block types:
            0x0001  Extra data area present  → extra_data_size field follows.
            0x0002  Data area present        → data_size field follows; actual
                                               data bytes come after the header
                                               in the outer stream.
            0x0004  Unknown-type block with this flag must be skipped on update.
            0x0008  Data area continues from the previous volume.
            0x0010  Data area continues in the next volume.
            0x0020  Block depends on the preceding file block.
            0x0040  Preserve child block if host block is modified.

      - id: extra_data_size
        type: vint
        if: (header_flags.value & 0x0001) != 0
        doc: |
          Total byte size of the extra data area appended after the type-specific
          body.  Present only when Header flags bit 0x0001 is set.

      - id: data_size
        type: vint
        if: (header_flags.value & 0x0002) != 0
        doc: |
          Byte size of the block data area that follows the header in the outer
          stream.  Present only when Header flags bit 0x0002 is set.

      - id: body
        doc: Type-specific header content, selected by header_type.
        type:
          switch-on: header_type.value
          cases:
            1: block_main
            2: block_file
            3: block_file   # service header uses identical structure to file header
            4: block_crypt
            5: block_eos

      - id: extra_data
        size: extra_data_size.value
        type: extra_data_area
        if: (header_flags.value & 0x0001) != 0
        doc: |
          Typed extra records (extended timestamps, per-file encryption,
          redirection targets, Unix ownership, etc.).  Present when Header
          flags bit 0x0001 is set; size is exactly extra_data_size bytes.

    instances:
      has_data:
        value: (header_flags.value & 0x0002) != 0
        doc: True when a data area follows this header in the outer stream.

      data_size_value:
        value: (header_flags.value & 0x0002) != 0 ? data_size.value : 0
        doc: |
          Data area size in bytes, or 0 when no data area is present.
          Safe to use without a separate has_data check.

      is_continuing_from_prev:
        value: (header_flags.value & 0x0008) != 0
        doc: True when the data area is a continuation from the previous volume.

      is_continuing_to_next:
        value: (header_flags.value & 0x0010) != 0
        doc: True when the data area continues in the next volume.

  # ============================================================
  #  Block type 1 — Main archive header
  # ============================================================
  block_main:
    doc: |
      Main archive header (block type 1).  Appears exactly once per volume.
      Conveys archive-wide properties: whether the archive spans multiple
      volumes, whether headers are encrypted, whether compression is solid, etc.

    seq:
      - id: archive_flags
        type: vint
        doc: |
          Archive-level attribute flags:
            0x0001  VOLUME        Archive is part of a multi-volume set.
            0x0002  COMMENT       A CMT service record (archive comment) is present.
            0x0004  LOCK          Archive lock attribute is set.
            0x0008  SOLID         Solid archive — files share a compression dictionary.
            0x0010  NEWNUMBERING  New naming scheme: name.partN.rar (volumes 2..N only).
            0x0020  ENCHEADERS    All subsequent headers are AES-256 encrypted.
            0x0040  FIRSTVOLUME   This is the first volume of a multi-volume set.

      - id: volume_number
        type: vint
        if: (archive_flags.value & 0x0010) != 0 and (archive_flags.value & 0x0040) == 0
        doc: |
          1-based volume index within a multi-volume archive.
          Present only when NEWNUMBERING (0x0010) is set and this is NOT the
          first volume (i.e., FIRSTVOLUME 0x0040 is clear).

    instances:
      is_volume:
        value: (archive_flags.value & 0x0001) != 0
        doc: Archive is part of a multi-volume set.
      has_comment:
        value: (archive_flags.value & 0x0002) != 0
        doc: A CMT service record is present in this archive.
      is_locked:
        value: (archive_flags.value & 0x0004) != 0
        doc: Archive lock attribute is set.
      is_solid:
        value: (archive_flags.value & 0x0008) != 0
        doc: Solid archive — files share a common compression history.
      has_new_numbering:
        value: (archive_flags.value & 0x0010) != 0
        doc: Volumes use the new naming scheme (name.partN.rar).
      has_enc_headers:
        value: (archive_flags.value & 0x0020) != 0
        doc: Headers are AES-256 encrypted; a `block_crypt` precedes this block.
      is_first_volume:
        value: (archive_flags.value & 0x0040) != 0
        doc: This is the first (or only) volume of the archive.

  # ============================================================
  #  Block type 2 — File header
  #  Block type 3 — Service header  (identical binary layout)
  # ============================================================
  block_file:
    doc: |
      File header (block type 2) or service header (block type 3).

      Service headers share this binary structure but carry reserved names
      that identify their purpose:
        "CMT"  Archive comment
        "QO"   Quick-open table (positional index for fast archive scanning)
        "ACL"  NTFS access control list
        "STM"  NTFS alternate data stream
        "RR"   Recovery record

      The modification time (mtime) is always present as a 4-byte field.
      Its interpretation depends on the UTIME flag:
        UTIME set   → Unix time_t (seconds since 1970-01-01 UTC)
        UTIME clear → Low 32 bits of a Windows FILETIME
                      (100-ns intervals since 1601-01-01 UTC)

      File names use UTF-8 encoding with '/' as the directory separator.

    seq:
      - id: file_flags
        type: vint
        doc: |
          File-specific flags:
            0x0001  ISDIR           Entry is a directory (no data area).
            0x0002  UTIME           mtime is a Unix timestamp; else Windows FILETIME.
            0x0004  CRC32           data_crc32 field is present.
            0x0008  UNPSIZE_UNKNOWN Unpacked size unknown; treat unpacked_size as 0.

      - id: unpacked_size
        type: vint
        doc: |
          Uncompressed size in bytes.  Ignore when UNPSIZE_UNKNOWN (0x0008) is set.

      - id: attributes
        type: vint
        doc: |
          File-system-specific attributes.
            host_os = 0 (Windows) → WIN32 FILE_ATTRIBUTE_* bitmask.
            host_os = 1 (Unix)    → Unix st_mode permission bits (low 16 bits).

      - id: mtime
        type: u4
        doc: |
          File modification time — always present.
          UTIME flag set   → Unix time_t (u32, seconds since 1970-01-01 UTC).
          UTIME flag clear → Low 32 bits of Windows FILETIME
                             (100-ns ticks since 1601-01-01 UTC).

      - id: data_crc32
        type: u4
        if: (file_flags.value & 0x0004) != 0
        doc: |
          CRC32 of the unpacked file data.  Present only when CRC32 (0x0004) flag is set.

      - id: compression_info
        type: vint
        doc: |
          Packed compression parameters (single vint, bit fields):
            bits  5..0   Version  RAR compression algorithm version; must be 50.
            bit      6   Solid    File uses compression state from preceding files.
            bits  9..7   Method   0 = storing, 1 = fastest … 5 = best  (see compression_method enum).
            bits 14..10  Dict     Dictionary size index; actual size = 128 KB << index.
                                  Index 15 means "use the archive-default dictionary".

      - id: host_os
        type: vint
        doc: |
          Operating system that created this entry (see `host_os` enum):
            0 = Windows
            1 = Unix

      - id: name_length
        type: vint
        doc: Length of the `name` field in bytes (byte count, not character count).

      - id: name
        type: str
        size: name_length.value
        encoding: UTF-8
        doc: |
          File or directory name encoded as UTF-8.  Directory components are
          separated by '/' (never '\').  The path is relative and never begins
          with '/'.  Example: "subdir/movie.mkv".

    instances:
      is_dir:
        value: (file_flags.value & 0x0001) != 0
        doc: True when this entry represents a directory.
      has_unix_mtime:
        value: (file_flags.value & 0x0002) != 0
        doc: True when mtime is a Unix timestamp (false → Windows FILETIME).
      has_crc32:
        value: (file_flags.value & 0x0004) != 0
        doc: True when data_crc32 is present.
      is_size_unknown:
        value: (file_flags.value & 0x0008) != 0
        doc: True when the unpacked size is not known at archive creation time.
      compression_version:
        value: compression_info.value & 0x3f
        doc: Compression algorithm version (should be 50 for RAR 5.0).
      is_solid_file:
        value: (compression_info.value & 0x40) != 0
        doc: True when this file uses solid compression (shares dictionary with prior files).
      compression_method:
        value: (compression_info.value >> 7) & 0x07
        doc: |
          Compression method index (see `compression_method` enum):
            0 = storing (no compression)
            1 = fastest … 5 = best
      dict_size_index:
        value: (compression_info.value >> 10) & 0x1f
        doc: |
          Dictionary size index (5 bits, bits 14..10 of compression_info).
          Actual dictionary size = 128 KB << dict_size_index.
          Value 15 means "use the archive-default dictionary size".

  # ============================================================
  #  Block type 4 — Archive encryption header
  # ============================================================
  block_crypt:
    doc: |
      Archive encryption header (block type 4).

      Present only when the archive was created with encrypted headers
      (main archive header flag ENCHEADERS 0x0020).  It appears BEFORE
      the (encrypted) main archive header.  It provides the KDF parameters
      needed to derive the AES-256 key that decrypts all subsequent headers.

      Key derivation: PBKDF2-HMAC-SHA256
        password   : user-supplied passphrase (UTF-8, NUL-terminated)
        salt       : 16 random bytes from this header
        iterations : 2^(kdf_count + 16)
        output     : 32 bytes → AES-256 key

    seq:
      - id: version
        type: vint
        doc: Encryption version; must be 0 for RAR 5.0.

      - id: enc_flags
        type: vint
        doc: |
          Encryption flags:
            0x0001  CHECK_PRESENT  Password-check data is present (check_value field follows).

      - id: kdf_count
        type: u1
        doc: |
          PBKDF2 iteration exponent.
          Actual iteration count = 2^(kdf_count + 16).
          A value of 0 yields 65 536 iterations; typical values are in the range 0..15.

      - id: salt
        size: 16
        doc: Random 128-bit salt used as PBKDF2 input.

      - id: check_value
        size: 12
        if: (enc_flags.value & 0x0001) != 0
        doc: |
          12-byte password-check blob.
          First 8 bytes: derived via an additional PBKDF2 round from password + salt.
          Last  4 bytes: CRC32 of those 8 bytes.
          Allows early rejection of wrong passwords before attempting full decryption.

    instances:
      has_check_value:
        value: (enc_flags.value & 0x0001) != 0
        doc: True when the check_value field is present.

  # ============================================================
  #  Block type 5 — End of archive header
  # ============================================================
  block_eos:
    doc: |
      End of archive header (block type 5).  Always the last header in a RAR5
      volume.  A parser should stop reading blocks after encountering this type.

    seq:
      - id: end_flags
        type: vint
        doc: |
          End-of-archive flags:
            0x0001  NEXT_VOLUME  Another volume follows in a multi-volume set.
                                 If this flag is clear, this is the final (or only) volume.

    instances:
      has_next_volume:
        value: (end_flags.value & 0x0001) != 0
        doc: True when another archive volume follows.

  # ============================================================
  #  Extra data area  (appended to file / service / main headers)
  # ============================================================
  extra_data_area:
    doc: |
      Sequence of typed extra records that extends a block header with optional
      metadata: extended timestamps, per-file encryption parameters, file-system
      redirection targets, Unix ownership data, and more.

      Located at the very end of the header sub-stream; sized by the parent
      header's extra_data_size field.

    seq:
      - id: records
        type: extra_record
        repeat: eos
        doc: |
          Variable-length array of extra records.  Parsing continues until the
          header sub-stream is exhausted (no explicit record count is stored).

  extra_record:
    doc: |
      A single extra data record.  The record is self-describing: the `size`
      field declares the total byte count of everything that follows it
      (type vint + data bytes), so unknown record types can be skipped safely.

    seq:
      - id: size
        type: vint
        doc: |
          Number of bytes in this record NOT including the size field itself.
          Covers the record_type vint and all subsequent record data.

      - id: body
        size: size.value
        type: extra_record_body
        doc: Record contents, parsed from a size-bounded sub-stream.

  extra_record_body:
    doc: Typed record body beginning with a vint type discriminator.
    seq:
      - id: record_type
        type: vint
        doc: Record type identifier — see `extra_record_type` enum.

      - id: data
        size-eos: true
        doc: |
          Raw record-specific payload.  Detailed per-type field layouts:

            CRYPT  (0x0001)  Per-file encryption: version(vint), flags(vint),
                             kdf_count(u1), salt[16], check_value[12]?, iv[16].
            HASH   (0x0002)  File hash: hash_type(vint), hash_data[...].
                             hash_type 0 = BLAKE2sp (32 bytes).
            HTIME  (0x0003)  Extended timestamps: flags(vint), optional
                             mtime(u4/u8), atime(u4/u8), ctime(u4/u8).
                             Flags bit 0x0001 = Unix format;
                             bit 0x0002/0x0004/0x0008 = mtime/atime/ctime present;
                             bit 0x0010 = nanoseconds present (additional u4 each).
            VERSION(0x0004)  File version: flags(vint), version_number(vint).
            REDIR  (0x0005)  Redirection: type(vint), flags(vint),
                             name_length(vint), name[name_length].
            UOWNER (0x0006)  Unix owner/group: flags(vint), optional
                             uid(vint), gid(vint), user_name[...], group_name[...].
            SUBDATA(0x0007)  Service-header-specific data (opaque).

    instances:
      record_type_enum:
        value: record_type.value.as<extra_record_type>
        doc: Record type as a typed enum value.

enums:

  # Block type identifiers (stored in header_type vint)
  block_type:
    1: main
    2: file
    3: service
    4: crypt
    5: eos

  # Extra data record type identifiers (stored in extra_record_body.record_type)
  extra_record_type:
    0x0001: crypt    # per-file encryption parameters
    0x0002: hash     # file hash (BLAKE2sp or other)
    0x0003: htime    # extended timestamps (mtime / atime / ctime with optional ns)
    0x0004: version  # file version number
    0x0005: redir    # file system redirection (symbolic / hard link)
    0x0006: uowner   # Unix owner and group info
    0x0007: subdata  # service-record-specific data

  # Compression method (bits 9..7 of block_file.compression_info)
  compression_method:
    0: storing   # no compression
    1: fastest
    2: fast
    3: normal
    4: good
    5: best

  # Host OS that created the entry (block_file.host_os)
  host_os:
    0: windows
    1: unix

