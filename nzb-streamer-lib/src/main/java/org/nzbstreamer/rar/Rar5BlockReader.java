package org.nzbstreamer.rar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The layout of a RAR 5.0 block.
 *
 * <p>A block starts with a CRC32 and the size of the header. The size counts from the byte after
 * itself to the end of the extra area. The data of the file comes after the header. Thus the
 * position of the data is the start of the header fields plus the size of the header.</p>
 *
 * <p>The fields agree with the RARLAB technical note for RAR 5.0.</p>
 */
final class Rar5BlockReader implements RarBlockReader {

    // Flags of the common header.
    private static final long HAS_EXTRA_AREA = 0x0001;
    private static final long HAS_DATA_AREA = 0x0002;
    private static final long SPLIT_BEFORE = 0x0008;
    private static final long SPLIT_AFTER = 0x0010;

    // Block types.
    private static final long TYPE_MAIN = 1;
    private static final long TYPE_FILE = 2;
    private static final long TYPE_SERVICE = 3;
    private static final long TYPE_ENCRYPTION = 4;
    private static final long TYPE_END = 5;

    // Flags of a file header.
    private static final long FILE_DIRECTORY = 0x0001;
    private static final long FILE_HAS_MTIME = 0x0002;
    private static final long FILE_HAS_CRC32 = 0x0004;

    // Flags of the main header.
    private static final long MAIN_VOLUME = 0x0001;
    private static final long MAIN_HAS_VOLUME_NUMBER = 0x0002;
    private static final long MAIN_SOLID = 0x0004;

    /** The type of the record in the extra area that shows that the data is encrypted. */
    private static final long EXTRA_ENCRYPTION = 0x01;

    private static final int MAX_NAME_SIZE = 64 * 1024;

    private RarVolumeInfo volumeInfo = RarVolumeInfo.SINGLE_FILE;

    @Override
    public RarFormat format() {
        return RarFormat.RAR5;
    }

    @Override
    public RarVolumeInfo volumeInfo() {
        return volumeInfo;
    }

    @Override
    public RarBlock readBlock(RarHeaderReader reader) throws IOException {
        long blockStart = reader.position();
        reader.readU32(); // The CRC32 of the header. The parser does not do a check.
        long headerSize = reader.readVint();
        long headerStart = reader.position();
        long typeCode = reader.readVint();
        long flags = reader.readVint();
        long extraSize = (flags & HAS_EXTRA_AREA) != 0 ? reader.readVint() : 0;
        long dataSize = (flags & HAS_DATA_AREA) != 0 ? reader.readVint() : 0;

        if (headerSize <= 0) {
            throw new RarParseException("RAR5 block at " + blockStart + " declares header size "
                    + headerSize);
        }
        long headerEnd = headerStart + headerSize;
        if (extraSize < 0 || extraSize > headerSize || dataSize < 0) {
            throw new RarParseException("RAR5 block at " + blockStart + " has inconsistent sizes"
                    + " (header " + headerSize + ", extra " + extraSize + ", data " + dataSize + ")");
        }
        if (typeCode == TYPE_ENCRYPTION) {
            throw new RarParseException("Archive has encrypted headers; cannot read entries without"
                    + " the password");
        }

        RarBlockInfo info = new RarBlockInfo(blockType(typeCode), blockStart, headerEnd - blockStart,
                headerEnd, dataSize, flags);

        if (typeCode == TYPE_FILE || typeCode == TYPE_SERVICE) {
            return readFileHeader(reader, info, headerEnd, extraSize);
        }
        if (typeCode == TYPE_MAIN) {
            readMainHeader(reader);
        }
        return new RarGenericBlock(info);
    }

    private void readMainHeader(RarHeaderReader reader) throws IOException {
        long archiveFlags = reader.readVint();
        boolean volume = (archiveFlags & MAIN_VOLUME) != 0;
        boolean solid = (archiveFlags & MAIN_SOLID) != 0;
        int volumeNumber = (archiveFlags & MAIN_HAS_VOLUME_NUMBER) != 0
                ? (int) reader.readVint()
                : 0;
        volumeInfo = new RarVolumeInfo(volume, volumeNumber, volume && volumeNumber == 0, solid);
    }

    /** A service block has the same fields as a file block. */
    private RarFileEntry readFileHeader(RarHeaderReader reader, RarBlockInfo info, long headerEnd,
                                        long extraSize) throws IOException {
        long fileFlags = reader.readVint();
        long unpackedSize = reader.readVint();
        reader.readVint(); // The file attributes. Their meaning changes with the host OS.
        Long mtime = (fileFlags & FILE_HAS_MTIME) != 0 ? reader.readU32() : null;
        Long crc32 = (fileFlags & FILE_HAS_CRC32) != 0 ? reader.readU32() : null;
        long compressionInfo = reader.readVint();
        long hostOs = reader.readVint();
        String name = readName(reader, info.headerOffset(), headerEnd);

        boolean encrypted = extraSize > 0
                && hasEncryptionRecord(reader, headerEnd - extraSize, headerEnd);

        RarCompression compression = new RarCompression((int) ((compressionInfo >>> 7) & 0x07),
                (compressionInfo & 0x40) != 0);
        RarFileFlags flags = new RarFileFlags((fileFlags & FILE_DIRECTORY) != 0, encrypted,
                (info.flags() & SPLIT_BEFORE) != 0, (info.flags() & SPLIT_AFTER) != 0);

        return new RarFileEntry(info, name, unpackedSize, compression, flags, crc32, mtime,
                (int) hostOs);
    }

    private String readName(RarHeaderReader reader, long blockStart, long headerEnd)
            throws IOException {
        long nameSize = reader.readVint();
        if (nameSize < 0 || nameSize > MAX_NAME_SIZE) {
            throw new RarParseException("RAR5 file header at " + blockStart + " declares name size "
                    + nameSize);
        }
        if (reader.position() + nameSize > headerEnd) {
            throw new RarParseException("RAR5 file header at " + blockStart + " has a name running"
                    + " past the end of its header");
        }
        return new String(reader.readBytes((int) nameSize), StandardCharsets.UTF_8);
    }

    /**
     * Reads the records of the extra area and finds the record for encryption. Each record has
     * three parts: the size, the type and the data. The size gives the length of the two parts
     * that come after the size.
     */
    private boolean hasEncryptionRecord(RarHeaderReader reader, long extraStart, long headerEnd)
            throws IOException {
        if (extraStart < reader.position()) {
            // The cursor is after the extra area. The header is bad, but the other blocks are
            // possibly correct. Thus the parser continues.
            return false;
        }
        reader.skipTo(extraStart);
        while (reader.position() < headerEnd) {
            long recordSize = reader.readVint();
            long afterSize = reader.position();
            if (recordSize <= 0 || afterSize + recordSize > headerEnd) {
                break;
            }
            if (reader.readVint() == EXTRA_ENCRYPTION) {
                reader.skipTo(headerEnd);
                return true;
            }
            reader.skipTo(afterSize + recordSize);
        }
        return false;
    }

    private RarBlockType blockType(long typeCode) {
        if (typeCode == TYPE_MAIN) {
            return RarBlockType.MAIN;
        }
        if (typeCode == TYPE_FILE) {
            return RarBlockType.FILE;
        }
        if (typeCode == TYPE_SERVICE) {
            return RarBlockType.SERVICE;
        }
        if (typeCode == TYPE_END) {
            return RarBlockType.END;
        }
        if (typeCode == TYPE_ENCRYPTION) {
            return RarBlockType.ENCRYPTION;
        }
        return RarBlockType.UNKNOWN;
    }
}
