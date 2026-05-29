package org.katai;

// This is a generated file! Please edit source .ksy file and use kaitai-struct-compiler to rebuild

import io.kaitai.struct.ByteBufferKaitaiStream;
import io.kaitai.struct.KaitaiStruct;
import io.kaitai.struct.KaitaiStream;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;


/**
 * RAR 5.0 archive format parser.
 *
 * Based on the official RARLAB technical specification:
 * https://www.rarlab.com/technote.htm
 *
 * An archive is a flat sequence of self-describing blocks. Each block begins
 * with a CRC32 and a variable-length size field, which together allow a reader
 * to skip unknown block types safely.  All multi-byte integers are little-endian.
 * Variable-length integers (vint) use LEB128 unsigned encoding (see `vint` type).
 *
 * Typical archive layout:
 *   1. 8-byte magic signature
 *   2. Optional `block_crypt` (type 4) — only if headers are encrypted
 *   3. `block_main`  (type 1) — one per volume
 *   4. `block_file`  (type 2) — one per stored file/directory
 *   5. `block_file`  (type 3, service header) — optional; CMT, QO, ACL, …
 *   6. `block_eos`   (type 5) — end of volume marker
 */
public class Rar5 extends KaitaiStruct {
    public static Rar5 fromFile(String fileName) throws IOException {
        return new Rar5(new ByteBufferKaitaiStream(fileName));
    }

    public enum BlockType {
        MAIN(1),
        FILE(2),
        SERVICE(3),
        CRYPT(4),
        EOS(5);

        private final long id;
        BlockType(long id) { this.id = id; }
        public long id() { return id; }
        private static final Map<Long, BlockType> byId = new HashMap<Long, BlockType>(5);
        static {
            for (BlockType e : BlockType.values())
                byId.put(e.id(), e);
        }
        public static BlockType byId(long id) { return byId.get(id); }
    }

    public enum CompressionMethod {
        STORING(0),
        FASTEST(1),
        FAST(2),
        NORMAL(3),
        GOOD(4),
        BEST(5);

        private final long id;
        CompressionMethod(long id) { this.id = id; }
        public long id() { return id; }
        private static final Map<Long, CompressionMethod> byId = new HashMap<Long, CompressionMethod>(6);
        static {
            for (CompressionMethod e : CompressionMethod.values())
                byId.put(e.id(), e);
        }
        public static CompressionMethod byId(long id) { return byId.get(id); }
    }

    public enum ExtraRecordType {
        CRYPT(1),
        HASH(2),
        HTIME(3),
        VERSION(4),
        REDIR(5),
        UOWNER(6),
        SUBDATA(7);

        private final long id;
        ExtraRecordType(long id) { this.id = id; }
        public long id() { return id; }
        private static final Map<Long, ExtraRecordType> byId = new HashMap<Long, ExtraRecordType>(7);
        static {
            for (ExtraRecordType e : ExtraRecordType.values())
                byId.put(e.id(), e);
        }
        public static ExtraRecordType byId(long id) { return byId.get(id); }
    }

    public enum HostOs {
        WINDOWS(0),
        UNIX(1);

        private final long id;
        HostOs(long id) { this.id = id; }
        public long id() { return id; }
        private static final Map<Long, HostOs> byId = new HashMap<Long, HostOs>(2);
        static {
            for (HostOs e : HostOs.values())
                byId.put(e.id(), e);
        }
        public static HostOs byId(long id) { return byId.get(id); }
    }

    public Rar5(KaitaiStream _io) {
        this(_io, null, null);
    }

    public Rar5(KaitaiStream _io, KaitaiStruct _parent) {
        this(_io, _parent, null);
    }

    public Rar5(KaitaiStream _io, KaitaiStruct _parent, Rar5 _root) {
        super(_io);
        this._parent = _parent;
        this._root = _root == null ? this : _root;
        _read();
    }
    private void _read() {
        this.signature = this._io.readBytes(8);
        if (!(Arrays.equals(this.signature, new byte[] { 82, 97, 114, 33, 26, 7, 1, 0 }))) {
            throw new KaitaiStream.ValidationNotEqualError(new byte[] { 82, 97, 114, 33, 26, 7, 1, 0 }, this.signature, this._io, "/seq/0");
        }
        this.blocks = new ArrayList<Block>();
        {
            int i = 0;
            while (!this._io.isEof()) {
                this.blocks.add(new Block(this._io, this, _root));
                i++;
            }
        }
    }

    public void _fetchInstances() {
        for (int i = 0; i < this.blocks.size(); i++) {
            this.blocks.get(((Number) (i)).intValue())._fetchInstances();
        }
    }

    /**
     * Outer wrapper for every RAR5 header block.
     *
     * Field layout in the archive stream:
     *   header_crc32  (4 bytes)    CRC32 of all bytes from header_type through end of extra data.
     *   header_size   (vint)       Byte count of the span covered by the CRC (does NOT include
     *                              header_crc32 itself nor the header_size vint).
     *   header_body   (N bytes)    Parsed via a bounded sub-stream of exactly header_size bytes.
     *   data          (M bytes)    Optional payload (e.g. compressed file content) that follows
     *                              the header.  Present when Header flags bit 0x0002 is set;
     *                              M is given by header_body.data_size_value.
     */
    public static class Block extends KaitaiStruct {
        public static Block fromFile(String fileName) throws IOException {
            return new Block(new ByteBufferKaitaiStream(fileName));
        }

        public Block(KaitaiStream _io) {
            this(_io, null, null);
        }

        public Block(KaitaiStream _io, Rar5 _parent) {
            this(_io, _parent, null);
        }

        public Block(KaitaiStream _io, Rar5 _parent, Rar5 _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.headerCrc32 = this._io.readU4le();
            this.headerSize = new Vint(this._io, this, _root);
            KaitaiStream _io_headerBody = this._io.substream(headerSize().value());
            this.headerBody = new BlockHeaderBody(_io_headerBody, this, _root);
            if (headerBody().hasData()) {
                this.data = this._io.readBytes(headerBody().dataSizeValue());
            }
        }

        public void _fetchInstances() {
            this.headerSize._fetchInstances();
            this.headerBody._fetchInstances();
            if (headerBody().hasData()) {
            }
        }
        private Rar5.BlockCrypt asCrypt;

        /**
         * Body cast to block_crypt.  Only valid when is_crypt is true.
         */
        public Rar5.BlockCrypt asCrypt() {
            if (this.asCrypt != null)
                return this.asCrypt;
            if (headerBody().headerType().value() == 4) {
                this.asCrypt = ((Rar5.BlockCrypt) (headerBody().body()));
            }
            return this.asCrypt;
        }
        private Rar5.BlockEos asEos;

        /**
         * Body cast to block_eos.  Only valid when is_eos is true.
         */
        public Rar5.BlockEos asEos() {
            if (this.asEos != null)
                return this.asEos;
            if (headerBody().headerType().value() == 5) {
                this.asEos = ((Rar5.BlockEos) (headerBody().body()));
            }
            return this.asEos;
        }
        private Rar5.BlockFile asFile;

        /**
         * Body cast to block_file.  Only valid when is_file is true.
         */
        public Rar5.BlockFile asFile() {
            if (this.asFile != null)
                return this.asFile;
            if (headerBody().headerType().value() == 2) {
                this.asFile = ((Rar5.BlockFile) (headerBody().body()));
            }
            return this.asFile;
        }
        private Rar5.BlockMain asMain;

        /**
         * Body cast to block_main.  Only valid when is_main is true.
         */
        public Rar5.BlockMain asMain() {
            if (this.asMain != null)
                return this.asMain;
            if (headerBody().headerType().value() == 1) {
                this.asMain = ((Rar5.BlockMain) (headerBody().body()));
            }
            return this.asMain;
        }
        private Rar5.BlockFile asService;

        /**
         * Body cast to block_file (service variant).  Only valid when is_service is true.
         * Check the name field for the service record identifier ("CMT", "QO", "ACL", etc.).
         */
        public Rar5.BlockFile asService() {
            if (this.asService != null)
                return this.asService;
            if (headerBody().headerType().value() == 3) {
                this.asService = ((Rar5.BlockFile) (headerBody().body()));
            }
            return this.asService;
        }
        private BlockType blockTypeEnum;

        /**
         * Block type as a typed enum; avoids raw integer comparisons.
         */
        public BlockType blockTypeEnum() {
            if (this.blockTypeEnum != null)
                return this.blockTypeEnum;
            this.blockTypeEnum = Rar5.BlockType.byId(headerBody().headerType().value());
            return this.blockTypeEnum;
        }
        private Boolean isCrypt;

        /**
         * True when this block is an archive encryption header (type 4).
         */
        public Boolean isCrypt() {
            if (this.isCrypt != null)
                return this.isCrypt;
            this.isCrypt = headerBody().headerType().value() == 4;
            return this.isCrypt;
        }
        private Boolean isEos;

        /**
         * True when this block is an end-of-archive header (type 5).
         */
        public Boolean isEos() {
            if (this.isEos != null)
                return this.isEos;
            this.isEos = headerBody().headerType().value() == 5;
            return this.isEos;
        }
        private Boolean isFile;

        /**
         * True when this block is a file header (type 2).
         */
        public Boolean isFile() {
            if (this.isFile != null)
                return this.isFile;
            this.isFile = headerBody().headerType().value() == 2;
            return this.isFile;
        }
        private Boolean isMain;

        /**
         * True when this block is a main archive header (type 1).
         */
        public Boolean isMain() {
            if (this.isMain != null)
                return this.isMain;
            this.isMain = headerBody().headerType().value() == 1;
            return this.isMain;
        }
        private Boolean isService;

        /**
         * True when this block is a service header (type 3; same structure as file header).
         */
        public Boolean isService() {
            if (this.isService != null)
                return this.isService;
            this.isService = headerBody().headerType().value() == 3;
            return this.isService;
        }
        private long headerCrc32;
        private Vint headerSize;
        private BlockHeaderBody headerBody;
        private byte[] data;
        private Rar5 _root;
        private Rar5 _parent;

        /**
         * CRC32 covering all header bytes from header_type through the last
         * byte of the extra data area.  Used to detect header corruption.
         */
        public long headerCrc32() { return headerCrc32; }

        /**
         * Size in bytes of the header data that immediately follows this field.
         * Covers header_type, header_flags, the two optional vint size fields,
         * the type-specific body, and the extra data area.
         */
        public Vint headerSize() { return headerSize; }

        /**
         * Bounded parse of the header contents.  Parsed from a sub-stream of
         * exactly header_size.value bytes so that unknown trailing fields in
         * future block types are automatically skipped.
         */
        public BlockHeaderBody headerBody() { return headerBody; }

        /**
         * Block data area.  For file blocks this contains the compressed file
         * payload; for service blocks it holds service-specific binary data.
         * Only present when Header flags bit 0x0002 is set.
         */
        public byte[] data() { return data; }
        public Rar5 _root() { return _root; }
        public Rar5 _parent() { return _parent; }
    }

    /**
     * Archive encryption header (block type 4).
     *
     * Present only when the archive was created with encrypted headers
     * (main archive header flag ENCHEADERS 0x0020).  It appears BEFORE
     * the (encrypted) main archive header.  It provides the KDF parameters
     * needed to derive the AES-256 key that decrypts all subsequent headers.
     *
     * Key derivation: PBKDF2-HMAC-SHA256
     *   password   : user-supplied passphrase (UTF-8, NUL-terminated)
     *   salt       : 16 random bytes from this header
     *   iterations : 2^(kdf_count + 16)
     *   output     : 32 bytes → AES-256 key
     */
    public static class BlockCrypt extends KaitaiStruct {
        public static BlockCrypt fromFile(String fileName) throws IOException {
            return new BlockCrypt(new ByteBufferKaitaiStream(fileName));
        }

        public BlockCrypt(KaitaiStream _io) {
            this(_io, null, null);
        }

        public BlockCrypt(KaitaiStream _io, Rar5.BlockHeaderBody _parent) {
            this(_io, _parent, null);
        }

        public BlockCrypt(KaitaiStream _io, Rar5.BlockHeaderBody _parent, Rar5 _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.version = new Vint(this._io, this, _root);
            this.encFlags = new Vint(this._io, this, _root);
            this.kdfCount = this._io.readU1();
            this.salt = this._io.readBytes(16);
            if ((encFlags().value() & 1) != 0) {
                this.checkValue = this._io.readBytes(12);
            }
        }

        public void _fetchInstances() {
            this.version._fetchInstances();
            this.encFlags._fetchInstances();
            if ((encFlags().value() & 1) != 0) {
            }
        }
        private Boolean hasCheckValue;

        /**
         * True when the check_value field is present.
         */
        public Boolean hasCheckValue() {
            if (this.hasCheckValue != null)
                return this.hasCheckValue;
            this.hasCheckValue = (encFlags().value() & 1) != 0;
            return this.hasCheckValue;
        }
        private Vint version;
        private Vint encFlags;
        private int kdfCount;
        private byte[] salt;
        private byte[] checkValue;
        private Rar5 _root;
        private Rar5.BlockHeaderBody _parent;

        /**
         * Encryption version; must be 0 for RAR 5.0.
         */
        public Vint version() { return version; }

        /**
         * Encryption flags:
         *   0x0001  CHECK_PRESENT  Password-check data is present (check_value field follows).
         */
        public Vint encFlags() { return encFlags; }

        /**
         * PBKDF2 iteration exponent.
         * Actual iteration count = 2^(kdf_count + 16).
         * A value of 0 yields 65 536 iterations; typical values are in the range 0..15.
         */
        public int kdfCount() { return kdfCount; }

        /**
         * Random 128-bit salt used as PBKDF2 input.
         */
        public byte[] salt() { return salt; }

        /**
         * 12-byte password-check blob.
         * First 8 bytes: derived via an additional PBKDF2 round from password + salt.
         * Last  4 bytes: CRC32 of those 8 bytes.
         * Allows early rejection of wrong passwords before attempting full decryption.
         */
        public byte[] checkValue() { return checkValue; }
        public Rar5 _root() { return _root; }
        public Rar5.BlockHeaderBody _parent() { return _parent; }
    }

    /**
     * End of archive header (block type 5).  Always the last header in a RAR5
     * volume.  A parser should stop reading blocks after encountering this type.
     */
    public static class BlockEos extends KaitaiStruct {
        public static BlockEos fromFile(String fileName) throws IOException {
            return new BlockEos(new ByteBufferKaitaiStream(fileName));
        }

        public BlockEos(KaitaiStream _io) {
            this(_io, null, null);
        }

        public BlockEos(KaitaiStream _io, Rar5.BlockHeaderBody _parent) {
            this(_io, _parent, null);
        }

        public BlockEos(KaitaiStream _io, Rar5.BlockHeaderBody _parent, Rar5 _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.endFlags = new Vint(this._io, this, _root);
        }

        public void _fetchInstances() {
            this.endFlags._fetchInstances();
        }
        private Boolean hasNextVolume;

        /**
         * True when another archive volume follows.
         */
        public Boolean hasNextVolume() {
            if (this.hasNextVolume != null)
                return this.hasNextVolume;
            this.hasNextVolume = (endFlags().value() & 1) != 0;
            return this.hasNextVolume;
        }
        private Vint endFlags;
        private Rar5 _root;
        private Rar5.BlockHeaderBody _parent;

        /**
         * End-of-archive flags:
         *   0x0001  NEXT_VOLUME  Another volume follows in a multi-volume set.
         *                        If this flag is clear, this is the final (or only) volume.
         */
        public Vint endFlags() { return endFlags; }
        public Rar5 _root() { return _root; }
        public Rar5.BlockHeaderBody _parent() { return _parent; }
    }

    /**
     * File header (block type 2) or service header (block type 3).
     *
     * Service headers share this binary structure but carry reserved names
     * that identify their purpose:
     *   "CMT"  Archive comment
     *   "QO"   Quick-open table (positional index for fast archive scanning)
     *   "ACL"  NTFS access control list
     *   "STM"  NTFS alternate data stream
     *   "RR"   Recovery record
     *
     * The modification time (mtime) is always present as a 4-byte field.
     * Its interpretation depends on the UTIME flag:
     *   UTIME set   → Unix time_t (seconds since 1970-01-01 UTC)
     *   UTIME clear → Low 32 bits of a Windows FILETIME
     *                 (100-ns intervals since 1601-01-01 UTC)
     *
     * File names use UTF-8 encoding with '/' as the directory separator.
     */
    public static class BlockFile extends KaitaiStruct {
        public static BlockFile fromFile(String fileName) throws IOException {
            return new BlockFile(new ByteBufferKaitaiStream(fileName));
        }

        public BlockFile(KaitaiStream _io) {
            this(_io, null, null);
        }

        public BlockFile(KaitaiStream _io, Rar5.BlockHeaderBody _parent) {
            this(_io, _parent, null);
        }

        public BlockFile(KaitaiStream _io, Rar5.BlockHeaderBody _parent, Rar5 _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.fileFlags = new Vint(this._io, this, _root);
            this.unpackedSize = new Vint(this._io, this, _root);
            this.attributes = new Vint(this._io, this, _root);
            this.mtime = this._io.readU4le();
            if ((fileFlags().value() & 4) != 0) {
                this.dataCrc32 = this._io.readU4le();
            }
            this.compressionInfo = new Vint(this._io, this, _root);
            this.hostOs = new Vint(this._io, this, _root);
            this.nameLength = new Vint(this._io, this, _root);
            this.name = new String(this._io.readBytes(nameLength().value()), StandardCharsets.UTF_8);
        }

        public void _fetchInstances() {
            this.fileFlags._fetchInstances();
            this.unpackedSize._fetchInstances();
            this.attributes._fetchInstances();
            if ((fileFlags().value() & 4) != 0) {
            }
            this.compressionInfo._fetchInstances();
            this.hostOs._fetchInstances();
            this.nameLength._fetchInstances();
        }
        private Integer compressionMethod;

        /**
         * Compression method index (see `compression_method` enum):
         *   0 = storing (no compression)
         *   1 = fastest … 5 = best
         */
        public Integer compressionMethod() {
            if (this.compressionMethod != null)
                return this.compressionMethod;
            this.compressionMethod = ((Number) (compressionInfo().value() >> 7 & 7)).intValue();
            return this.compressionMethod;
        }
        private CompressionMethod compressionMethodEnum;

        /**
         * Compression method as a typed enum (same bits as compression_method).
         */
        public CompressionMethod compressionMethodEnum() {
            if (this.compressionMethodEnum != null)
                return this.compressionMethodEnum;
            this.compressionMethodEnum = Rar5.CompressionMethod.byId(compressionInfo().value() >> 7 & 7);
            return this.compressionMethodEnum;
        }
        private Integer compressionVersion;

        /**
         * Compression algorithm version (should be 50 for RAR 5.0).
         */
        public Integer compressionVersion() {
            if (this.compressionVersion != null)
                return this.compressionVersion;
            this.compressionVersion = ((Number) (compressionInfo().value() & 63)).intValue();
            return this.compressionVersion;
        }
        private Integer dictSizeIndex;

        /**
         * Dictionary size index (5 bits, bits 14..10 of compression_info).
         * Actual dictionary size = 128 KB << dict_size_index.
         * Value 15 means "use the archive-default dictionary size".
         */
        public Integer dictSizeIndex() {
            if (this.dictSizeIndex != null)
                return this.dictSizeIndex;
            this.dictSizeIndex = ((Number) (compressionInfo().value() >> 10 & 31)).intValue();
            return this.dictSizeIndex;
        }
        private Boolean hasCrc32;

        /**
         * True when data_crc32 is present.
         */
        public Boolean hasCrc32() {
            if (this.hasCrc32 != null)
                return this.hasCrc32;
            this.hasCrc32 = (fileFlags().value() & 4) != 0;
            return this.hasCrc32;
        }
        private Boolean hasUnixMtime;

        /**
         * True when mtime is a Unix timestamp (false → Windows FILETIME).
         */
        public Boolean hasUnixMtime() {
            if (this.hasUnixMtime != null)
                return this.hasUnixMtime;
            this.hasUnixMtime = (fileFlags().value() & 2) != 0;
            return this.hasUnixMtime;
        }
        private HostOs hostOsEnum;

        /**
         * Host OS as a typed enum (same value as host_os.value).
         */
        public HostOs hostOsEnum() {
            if (this.hostOsEnum != null)
                return this.hostOsEnum;
            this.hostOsEnum = Rar5.HostOs.byId(hostOs().value());
            return this.hostOsEnum;
        }
        private Boolean isDir;

        /**
         * True when this entry represents a directory.
         */
        public Boolean isDir() {
            if (this.isDir != null)
                return this.isDir;
            this.isDir = (fileFlags().value() & 1) != 0;
            return this.isDir;
        }
        private Boolean isSizeUnknown;

        /**
         * True when the unpacked size is not known at archive creation time.
         */
        public Boolean isSizeUnknown() {
            if (this.isSizeUnknown != null)
                return this.isSizeUnknown;
            this.isSizeUnknown = (fileFlags().value() & 8) != 0;
            return this.isSizeUnknown;
        }
        private Boolean isSolidFile;

        /**
         * True when this file uses solid compression (shares dictionary with prior files).
         */
        public Boolean isSolidFile() {
            if (this.isSolidFile != null)
                return this.isSolidFile;
            this.isSolidFile = (compressionInfo().value() & 64) != 0;
            return this.isSolidFile;
        }
        private Vint fileFlags;
        private Vint unpackedSize;
        private Vint attributes;
        private long mtime;
        private Long dataCrc32;
        private Vint compressionInfo;
        private Vint hostOs;
        private Vint nameLength;
        private String name;
        private Rar5 _root;
        private Rar5.BlockHeaderBody _parent;

        /**
         * File-specific flags:
         *   0x0001  ISDIR           Entry is a directory (no data area).
         *   0x0002  UTIME           mtime is a Unix timestamp; else Windows FILETIME.
         *   0x0004  CRC32           data_crc32 field is present.
         *   0x0008  UNPSIZE_UNKNOWN Unpacked size unknown; treat unpacked_size as 0.
         */
        public Vint fileFlags() { return fileFlags; }

        /**
         * Uncompressed size in bytes.  Ignore when UNPSIZE_UNKNOWN (0x0008) is set.
         */
        public Vint unpackedSize() { return unpackedSize; }

        /**
         * File-system-specific attributes.
         *   host_os = 0 (Windows) → WIN32 FILE_ATTRIBUTE_* bitmask.
         *   host_os = 1 (Unix)    → Unix st_mode permission bits (low 16 bits).
         */
        public Vint attributes() { return attributes; }

        /**
         * File modification time — always present.
         * UTIME flag set   → Unix time_t (u32, seconds since 1970-01-01 UTC).
         * UTIME flag clear → Low 32 bits of Windows FILETIME
         *                    (100-ns ticks since 1601-01-01 UTC).
         */
        public long mtime() { return mtime; }

        /**
         * CRC32 of the unpacked file data.  Present only when CRC32 (0x0004) flag is set.
         */
        public Long dataCrc32() { return dataCrc32; }

        /**
         * Packed compression parameters (single vint, bit fields):
         *   bits  5..0   Version  RAR compression algorithm version; must be 50.
         *   bit      6   Solid    File uses compression state from preceding files.
         *   bits  9..7   Method   0 = storing, 1 = fastest … 5 = best  (see compression_method enum).
         *   bits 14..10  Dict     Dictionary size index; actual size = 128 KB << index.
         *                         Index 15 means "use the archive-default dictionary".
         */
        public Vint compressionInfo() { return compressionInfo; }

        /**
         * Operating system that created this entry (see `host_os` enum):
         *   0 = Windows
         *   1 = Unix
         */
        public Vint hostOs() { return hostOs; }

        /**
         * Length of the `name` field in bytes (byte count, not character count).
         */
        public Vint nameLength() { return nameLength; }

        /**
         * File or directory name encoded as UTF-8.  Directory components are
         * separated by '/' (never '\').  The path is relative and never begins
         * with '/'.  Example: "subdir/movie.mkv".
         */
        public String name() { return name; }
        public Rar5 _root() { return _root; }
        public Rar5.BlockHeaderBody _parent() { return _parent; }
    }

    /**
     * Common fields present at the start of every block header, followed by
     * type-specific body data and an optional extra data area.  All fields are
     * read from the sub-stream bounded by the parent block's header_size.
     */
    public static class BlockHeaderBody extends KaitaiStruct {
        public static BlockHeaderBody fromFile(String fileName) throws IOException {
            return new BlockHeaderBody(new ByteBufferKaitaiStream(fileName));
        }

        public BlockHeaderBody(KaitaiStream _io) {
            this(_io, null, null);
        }

        public BlockHeaderBody(KaitaiStream _io, Rar5.Block _parent) {
            this(_io, _parent, null);
        }

        public BlockHeaderBody(KaitaiStream _io, Rar5.Block _parent, Rar5 _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.headerType = new Vint(this._io, this, _root);
            this.headerFlags = new Vint(this._io, this, _root);
            if ((headerFlags().value() & 1) != 0) {
                this.extraDataSize = new Vint(this._io, this, _root);
            }
            if ((headerFlags().value() & 2) != 0) {
                this.dataSize = new Vint(this._io, this, _root);
            }
            switch (headerType().value()) {
                case 1: {
                    this.body = new BlockMain(this._io, this, _root);
                    break;
                }
                case 2: {
                    this.body = new BlockFile(this._io, this, _root);
                    break;
                }
                case 3: {
                    this.body = new BlockFile(this._io, this, _root);
                    break;
                }
                case 4: {
                    this.body = new BlockCrypt(this._io, this, _root);
                    break;
                }
                case 5: {
                    this.body = new BlockEos(this._io, this, _root);
                    break;
                }
            }
            if ((headerFlags().value() & 1) != 0) {
                KaitaiStream _io_extraData = this._io.substream(extraDataSize().value());
                this.extraData = new ExtraDataArea(_io_extraData, this, _root);
            }
        }

        public void _fetchInstances() {
            this.headerType._fetchInstances();
            this.headerFlags._fetchInstances();
            if ((headerFlags().value() & 1) != 0) {
                this.extraDataSize._fetchInstances();
            }
            if ((headerFlags().value() & 2) != 0) {
                this.dataSize._fetchInstances();
            }
            switch (headerType().value()) {
                case 1: {
                    ((BlockMain) (this.body))._fetchInstances();
                    break;
                }
                case 2: {
                    ((BlockFile) (this.body))._fetchInstances();
                    break;
                }
                case 3: {
                    ((BlockFile) (this.body))._fetchInstances();
                    break;
                }
                case 4: {
                    ((BlockCrypt) (this.body))._fetchInstances();
                    break;
                }
                case 5: {
                    ((BlockEos) (this.body))._fetchInstances();
                    break;
                }
            }
            if ((headerFlags().value() & 1) != 0) {
                this.extraData._fetchInstances();
            }
        }
        private Integer dataSizeValue;

        /**
         * Data area size in bytes, or 0 when no data area is present.
         * Safe to use without a separate has_data check.
         */
        public Integer dataSizeValue() {
            if (this.dataSizeValue != null)
                return this.dataSizeValue;
            this.dataSizeValue = ((Number) (((headerFlags().value() & 2) != 0 ? dataSize().value() : 0))).intValue();
            return this.dataSizeValue;
        }
        private Boolean hasData;

        /**
         * True when a data area follows this header in the outer stream.
         */
        public Boolean hasData() {
            if (this.hasData != null)
                return this.hasData;
            this.hasData = (headerFlags().value() & 2) != 0;
            return this.hasData;
        }
        private BlockType headerTypeEnum;

        /**
         * Block type as a typed enum (same value as header_type.value but enum-typed).
         */
        public BlockType headerTypeEnum() {
            if (this.headerTypeEnum != null)
                return this.headerTypeEnum;
            this.headerTypeEnum = Rar5.BlockType.byId(headerType().value());
            return this.headerTypeEnum;
        }
        private Boolean isContinuingFromPrev;

        /**
         * True when the data area is a continuation from the previous volume.
         */
        public Boolean isContinuingFromPrev() {
            if (this.isContinuingFromPrev != null)
                return this.isContinuingFromPrev;
            this.isContinuingFromPrev = (headerFlags().value() & 8) != 0;
            return this.isContinuingFromPrev;
        }
        private Boolean isContinuingToNext;

        /**
         * True when the data area continues in the next volume.
         */
        public Boolean isContinuingToNext() {
            if (this.isContinuingToNext != null)
                return this.isContinuingToNext;
            this.isContinuingToNext = (headerFlags().value() & 16) != 0;
            return this.isContinuingToNext;
        }
        private Vint headerType;
        private Vint headerFlags;
        private Vint extraDataSize;
        private Vint dataSize;
        private KaitaiStruct body;
        private ExtraDataArea extraData;
        private Rar5 _root;
        private Rar5.Block _parent;

        /**
         * Block type identifier — see `block_type` enum.
         */
        public Vint headerType() { return headerType; }

        /**
         * Flags shared by all block types:
         *   0x0001  Extra data area present  → extra_data_size field follows.
         *   0x0002  Data area present        → data_size field follows; actual
         *                                      data bytes come after the header
         *                                      in the outer stream.
         *   0x0004  Unknown-type block with this flag must be skipped on update.
         *   0x0008  Data area continues from the previous volume.
         *   0x0010  Data area continues in the next volume.
         *   0x0020  Block depends on the preceding file block.
         *   0x0040  Preserve child block if host block is modified.
         */
        public Vint headerFlags() { return headerFlags; }

        /**
         * Total byte size of the extra data area appended after the type-specific
         * body.  Present only when Header flags bit 0x0001 is set.
         */
        public Vint extraDataSize() { return extraDataSize; }

        /**
         * Byte size of the block data area that follows the header in the outer
         * stream.  Present only when Header flags bit 0x0002 is set.
         */
        public Vint dataSize() { return dataSize; }

        /**
         * Type-specific header content, selected by header_type.
         */
        public KaitaiStruct body() { return body; }

        /**
         * Typed extra records (extended timestamps, per-file encryption,
         * redirection targets, Unix ownership, etc.).  Present when Header
         * flags bit 0x0001 is set; size is exactly extra_data_size bytes.
         */
        public ExtraDataArea extraData() { return extraData; }
        public Rar5 _root() { return _root; }
        public Rar5.Block _parent() { return _parent; }
    }

    /**
     * Main archive header (block type 1).  Appears exactly once per volume.
     * Conveys archive-wide properties: whether the archive spans multiple
     * volumes, whether headers are encrypted, whether compression is solid, etc.
     */
    public static class BlockMain extends KaitaiStruct {
        public static BlockMain fromFile(String fileName) throws IOException {
            return new BlockMain(new ByteBufferKaitaiStream(fileName));
        }

        public BlockMain(KaitaiStream _io) {
            this(_io, null, null);
        }

        public BlockMain(KaitaiStream _io, Rar5.BlockHeaderBody _parent) {
            this(_io, _parent, null);
        }

        public BlockMain(KaitaiStream _io, Rar5.BlockHeaderBody _parent, Rar5 _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.archiveFlags = new Vint(this._io, this, _root);
            if ( (((archiveFlags().value() & 16) != 0) && ((archiveFlags().value() & 64) == 0)) ) {
                this.volumeNumber = new Vint(this._io, this, _root);
            }
        }

        public void _fetchInstances() {
            this.archiveFlags._fetchInstances();
            if ( (((archiveFlags().value() & 16) != 0) && ((archiveFlags().value() & 64) == 0)) ) {
                this.volumeNumber._fetchInstances();
            }
        }
        private Boolean hasComment;

        /**
         * A CMT service record is present in this archive.
         */
        public Boolean hasComment() {
            if (this.hasComment != null)
                return this.hasComment;
            this.hasComment = (archiveFlags().value() & 2) != 0;
            return this.hasComment;
        }
        private Boolean hasEncHeaders;

        /**
         * Headers are AES-256 encrypted; a `block_crypt` precedes this block.
         */
        public Boolean hasEncHeaders() {
            if (this.hasEncHeaders != null)
                return this.hasEncHeaders;
            this.hasEncHeaders = (archiveFlags().value() & 32) != 0;
            return this.hasEncHeaders;
        }
        private Boolean hasNewNumbering;

        /**
         * Volumes use the new naming scheme (name.partN.rar).
         */
        public Boolean hasNewNumbering() {
            if (this.hasNewNumbering != null)
                return this.hasNewNumbering;
            this.hasNewNumbering = (archiveFlags().value() & 16) != 0;
            return this.hasNewNumbering;
        }
        private Boolean isFirstVolume;

        /**
         * This is the first (or only) volume of the archive.
         */
        public Boolean isFirstVolume() {
            if (this.isFirstVolume != null)
                return this.isFirstVolume;
            this.isFirstVolume = (archiveFlags().value() & 64) != 0;
            return this.isFirstVolume;
        }
        private Boolean isLocked;

        /**
         * Archive lock attribute is set.
         */
        public Boolean isLocked() {
            if (this.isLocked != null)
                return this.isLocked;
            this.isLocked = (archiveFlags().value() & 4) != 0;
            return this.isLocked;
        }
        private Boolean isSolid;

        /**
         * Solid archive — files share a common compression history.
         */
        public Boolean isSolid() {
            if (this.isSolid != null)
                return this.isSolid;
            this.isSolid = (archiveFlags().value() & 8) != 0;
            return this.isSolid;
        }
        private Boolean isVolume;

        /**
         * Archive is part of a multi-volume set.
         */
        public Boolean isVolume() {
            if (this.isVolume != null)
                return this.isVolume;
            this.isVolume = (archiveFlags().value() & 1) != 0;
            return this.isVolume;
        }
        private Vint archiveFlags;
        private Vint volumeNumber;
        private Rar5 _root;
        private Rar5.BlockHeaderBody _parent;

        /**
         * Archive-level attribute flags:
         *   0x0001  VOLUME        Archive is part of a multi-volume set.
         *   0x0002  COMMENT       A CMT service record (archive comment) is present.
         *   0x0004  LOCK          Archive lock attribute is set.
         *   0x0008  SOLID         Solid archive — files share a compression dictionary.
         *   0x0010  NEWNUMBERING  New naming scheme: name.partN.rar (volumes 2..N only).
         *   0x0020  ENCHEADERS    All subsequent headers are AES-256 encrypted.
         *   0x0040  FIRSTVOLUME   This is the first volume of a multi-volume set.
         */
        public Vint archiveFlags() { return archiveFlags; }

        /**
         * 1-based volume index within a multi-volume archive.
         * Present only when NEWNUMBERING (0x0010) is set and this is NOT the
         * first volume (i.e., FIRSTVOLUME 0x0040 is clear).
         */
        public Vint volumeNumber() { return volumeNumber; }
        public Rar5 _root() { return _root; }
        public Rar5.BlockHeaderBody _parent() { return _parent; }
    }

    /**
     * Sequence of typed extra records that extends a block header with optional
     * metadata: extended timestamps, per-file encryption parameters, file-system
     * redirection targets, Unix ownership data, and more.
     *
     * Located at the very end of the header sub-stream; sized by the parent
     * header's extra_data_size field.
     */
    public static class ExtraDataArea extends KaitaiStruct {
        public static ExtraDataArea fromFile(String fileName) throws IOException {
            return new ExtraDataArea(new ByteBufferKaitaiStream(fileName));
        }

        public ExtraDataArea(KaitaiStream _io) {
            this(_io, null, null);
        }

        public ExtraDataArea(KaitaiStream _io, Rar5.BlockHeaderBody _parent) {
            this(_io, _parent, null);
        }

        public ExtraDataArea(KaitaiStream _io, Rar5.BlockHeaderBody _parent, Rar5 _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.records = new ArrayList<ExtraRecord>();
            {
                int i = 0;
                while (!this._io.isEof()) {
                    this.records.add(new ExtraRecord(this._io, this, _root));
                    i++;
                }
            }
        }

        public void _fetchInstances() {
            for (int i = 0; i < this.records.size(); i++) {
                this.records.get(((Number) (i)).intValue())._fetchInstances();
            }
        }
        private List<ExtraRecord> records;
        private Rar5 _root;
        private Rar5.BlockHeaderBody _parent;

        /**
         * Variable-length array of extra records.  Parsing continues until the
         * header sub-stream is exhausted (no explicit record count is stored).
         */
        public List<ExtraRecord> records() { return records; }
        public Rar5 _root() { return _root; }
        public Rar5.BlockHeaderBody _parent() { return _parent; }
    }

    /**
     * A single extra data record.  The record is self-describing: the `size`
     * field declares the total byte count of everything that follows it
     * (type vint + data bytes), so unknown record types can be skipped safely.
     */
    public static class ExtraRecord extends KaitaiStruct {
        public static ExtraRecord fromFile(String fileName) throws IOException {
            return new ExtraRecord(new ByteBufferKaitaiStream(fileName));
        }

        public ExtraRecord(KaitaiStream _io) {
            this(_io, null, null);
        }

        public ExtraRecord(KaitaiStream _io, Rar5.ExtraDataArea _parent) {
            this(_io, _parent, null);
        }

        public ExtraRecord(KaitaiStream _io, Rar5.ExtraDataArea _parent, Rar5 _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.size = new Vint(this._io, this, _root);
            KaitaiStream _io_body = this._io.substream(size().value());
            this.body = new ExtraRecordBody(_io_body, this, _root);
        }

        public void _fetchInstances() {
            this.size._fetchInstances();
            this.body._fetchInstances();
        }
        private Vint size;
        private ExtraRecordBody body;
        private Rar5 _root;
        private Rar5.ExtraDataArea _parent;

        /**
         * Number of bytes in this record NOT including the size field itself.
         * Covers the record_type vint and all subsequent record data.
         */
        public Vint size() { return size; }

        /**
         * Record contents, parsed from a size-bounded sub-stream.
         */
        public ExtraRecordBody body() { return body; }
        public Rar5 _root() { return _root; }
        public Rar5.ExtraDataArea _parent() { return _parent; }
    }

    /**
     * Typed record body beginning with a vint type discriminator.
     */
    public static class ExtraRecordBody extends KaitaiStruct {
        public static ExtraRecordBody fromFile(String fileName) throws IOException {
            return new ExtraRecordBody(new ByteBufferKaitaiStream(fileName));
        }

        public ExtraRecordBody(KaitaiStream _io) {
            this(_io, null, null);
        }

        public ExtraRecordBody(KaitaiStream _io, Rar5.ExtraRecord _parent) {
            this(_io, _parent, null);
        }

        public ExtraRecordBody(KaitaiStream _io, Rar5.ExtraRecord _parent, Rar5 _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.recordType = new Vint(this._io, this, _root);
            this.data = this._io.readBytesFull();
        }

        public void _fetchInstances() {
            this.recordType._fetchInstances();
        }
        private ExtraRecordType recordTypeEnum;

        /**
         * Record type as a typed enum; avoids raw integer comparisons.
         */
        public ExtraRecordType recordTypeEnum() {
            if (this.recordTypeEnum != null)
                return this.recordTypeEnum;
            this.recordTypeEnum = Rar5.ExtraRecordType.byId(recordType().value());
            return this.recordTypeEnum;
        }
        private Vint recordType;
        private byte[] data;
        private Rar5 _root;
        private Rar5.ExtraRecord _parent;

        /**
         * Record type identifier.  Compare record_type.value against the
         * `extra_record_type` enum constants defined at the bottom of this file:
         *   0x0001  crypt    per-file encryption parameters
         *   0x0002  hash     file hash (BLAKE2sp or other)
         *   0x0003  htime    extended timestamps (mtime / atime / ctime, optional ns)
         *   0x0004  version  file version number
         *   0x0005  redir    file-system redirection (symbolic / hard link)
         *   0x0006  uowner   Unix owner and group info
         *   0x0007  subdata  service-record-specific data
         */
        public Vint recordType() { return recordType; }

        /**
         * Raw record-specific payload.  Detailed per-type field layouts:
         *
         *   CRYPT  (0x0001)  Per-file encryption: version(vint), flags(vint),
         *                    kdf_count(u1), salt[16], check_value[12]?, iv[16].
         *   HASH   (0x0002)  File hash: hash_type(vint), hash_data[...].
         *                    hash_type 0 = BLAKE2sp (32 bytes).
         *   HTIME  (0x0003)  Extended timestamps: flags(vint), optional
         *                    mtime(u4/u8), atime(u4/u8), ctime(u4/u8).
         *                    Flags bit 0x0001 = Unix format;
         *                    bit 0x0002/0x0004/0x0008 = mtime/atime/ctime present;
         *                    bit 0x0010 = nanoseconds present (additional u4 each).
         *   VERSION(0x0004)  File version: flags(vint), version_number(vint).
         *   REDIR  (0x0005)  Redirection: type(vint), flags(vint),
         *                    name_length(vint), name[name_length].
         *   UOWNER (0x0006)  Unix owner/group: flags(vint), optional
         *                    uid(vint), gid(vint), user_name[...], group_name[...].
         *   SUBDATA(0x0007)  Service-header-specific data (opaque).
         */
        public byte[] data() { return data; }
        public Rar5 _root() { return _root; }
        public Rar5.ExtraRecord _parent() { return _parent; }
    }

    /**
     * Unsigned variable-length integer (LEB128, little-endian base-128).
     *
     * Each byte encodes 7 data bits (bits 6..0); bit 7 is the continuation
     * flag (1 = more bytes follow, 0 = this is the last byte).  Bytes are
     * ordered from least-significant to most-significant.  At most 8 bytes
     * may be used, giving a maximum representable value of 2^56 − 1.
     *
     * Decoding example — value 300 (0x12C):
     *   byte 0: 0xAC  →  continuation=1, data=0x2C
     *   byte 1: 0x02  →  continuation=0, data=0x02
     *   value  = 0x2C | (0x02 << 7) = 44 | 256 = 300
     */
    public static class Vint extends KaitaiStruct {
        public static Vint fromFile(String fileName) throws IOException {
            return new Vint(new ByteBufferKaitaiStream(fileName));
        }

        public Vint(KaitaiStream _io) {
            this(_io, null, null);
        }

        public Vint(KaitaiStream _io, KaitaiStruct _parent) {
            this(_io, _parent, null);
        }

        public Vint(KaitaiStream _io, KaitaiStruct _parent, Rar5 _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.b0 = this._io.readU1();
            if ((b0() & 128) != 0) {
                this.b1 = this._io.readU1();
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0)) ) {
                this.b2 = this._io.readU1();
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0)) ) {
                this.b3 = this._io.readU1();
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0)) ) {
                this.b4 = this._io.readU1();
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0) && ((b4() & 128) != 0)) ) {
                this.b5 = this._io.readU1();
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0) && ((b4() & 128) != 0) && ((b5() & 128) != 0)) ) {
                this.b6 = this._io.readU1();
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0) && ((b4() & 128) != 0) && ((b5() & 128) != 0) && ((b6() & 128) != 0)) ) {
                this.b7 = this._io.readU1();
            }
        }

        public void _fetchInstances() {
            if ((b0() & 128) != 0) {
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0)) ) {
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0)) ) {
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0)) ) {
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0) && ((b4() & 128) != 0)) ) {
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0) && ((b4() & 128) != 0) && ((b5() & 128) != 0)) ) {
            }
            if ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0) && ((b4() & 128) != 0) && ((b5() & 128) != 0) && ((b6() & 128) != 0)) ) {
            }
        }
        private Integer value;

        /**
         * Decoded unsigned integer value.
         */
        public Integer value() {
            if (this.value != null)
                return this.value;
            this.value = ((Number) (((((((b0() & 127 | ((b0() & 128) != 0 ? (b1() & 127) << 7 : 0)) | ( (((b0() & 128) != 0) && ((b1() & 128) != 0))  ? (b2() & 127) << 14 : 0)) | ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0))  ? (b3() & 127) << 21 : 0)) | ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0))  ? (b4() & 127) << 28 : 0)) | ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0) && ((b4() & 128) != 0))  ? (b5() & 127) << 35 : 0)) | ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0) && ((b4() & 128) != 0) && ((b5() & 128) != 0))  ? (b6() & 127) << 42 : 0)) | ( (((b0() & 128) != 0) && ((b1() & 128) != 0) && ((b2() & 128) != 0) && ((b3() & 128) != 0) && ((b4() & 128) != 0) && ((b5() & 128) != 0) && ((b6() & 128) != 0))  ? (b7() & 127) << 49 : 0))).intValue();
            return this.value;
        }
        private int b0;
        private Integer b1;
        private Integer b2;
        private Integer b3;
        private Integer b4;
        private Integer b5;
        private Integer b6;
        private Integer b7;
        private Rar5 _root;
        private KaitaiStruct _parent;
        public int b0() { return b0; }
        public Integer b1() { return b1; }
        public Integer b2() { return b2; }
        public Integer b3() { return b3; }
        public Integer b4() { return b4; }
        public Integer b5() { return b5; }
        public Integer b6() { return b6; }
        public Integer b7() { return b7; }
        public Rar5 _root() { return _root; }
        public KaitaiStruct _parent() { return _parent; }
    }
    private byte[] signature;
    private List<Block> blocks;
    private Rar5 _root;
    private KaitaiStruct _parent;

    /**
     * RAR 5.0 magic bytes (8 bytes).
     * Distinguishes RAR5 from RAR4, which starts with 0x52 0x61 0x72 0x21 0x1a 0x07 0x00.
     */
    public byte[] signature() { return signature; }

    /**
     * Sequence of all archive blocks. Parsing continues until end-of-stream.
     * The last block in a valid archive is always a `block_eos` (type 5).
     */
    public List<Block> blocks() { return blocks; }
    public Rar5 _root() { return _root; }
    public KaitaiStruct _parent() { return _parent; }
}

