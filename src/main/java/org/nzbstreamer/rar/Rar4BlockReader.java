package org.nzbstreamer.rar;

import java.io.IOException;

/**
 * The layout of a RAR 1.5 to 4.x block.
 *
 * <p>A block starts with seven bytes: a CRC16, the type, the flags and the size of the header. The
 * size of the header includes those seven bytes. The data comes after the header.</p>
 *
 * <p>A file header gives the length of its data in the field {@code PACK_SIZE}. A different block
 * gives that length in the field {@code ADD_SIZE}, and only when the flag {@code 0x8000} is
 * set.</p>
 */
final class Rar4BlockReader implements RarBlockReader {

    // Block types.
    private static final int TYPE_MAIN = 0x73;
    private static final int TYPE_FILE = 0x74;
    private static final int TYPE_COMMENT = 0x75;
    private static final int TYPE_NEWSUB = 0x7A;
    private static final int TYPE_END = 0x7B;

    // Flags of a block header.
    private static final int LONG_BLOCK = 0x8000;

    // Flags of a file header.
    private static final int SPLIT_BEFORE = 0x0001;
    private static final int SPLIT_AFTER = 0x0002;
    private static final int ENCRYPTED = 0x0004;
    private static final int SOLID = 0x0010;
    private static final int DIRECTORY_MASK = 0x00E0;
    private static final int LARGE_FILE = 0x0100;
    private static final int UNICODE_NAME = 0x0200;

    // Flags of the main header.
    private static final int MAIN_VOLUME = 0x0001;
    private static final int MAIN_SOLID = 0x0008;
    private static final int MAIN_ENCRYPTED_HEADERS = 0x0080;
    private static final int MAIN_FIRST_VOLUME = 0x0100;

    private static final int BASE_HEADER_SIZE = 7;
    private static final int MAX_NAME_SIZE = 64 * 1024;
    private static final int METHOD_STORE = 0x30;

    private RarVolumeInfo volumeInfo = RarVolumeInfo.SINGLE_FILE;

    @Override
    public RarFormat format() {
        return RarFormat.RAR4;
    }

    @Override
    public RarVolumeInfo volumeInfo() {
        return volumeInfo;
    }

    @Override
    public RarBlock readBlock(RarHeaderReader reader) throws IOException {
        long blockStart = reader.position();
        reader.readU16(); // The CRC16 of the header. The parser does not do a check.
        int typeCode = reader.readU8();
        int flags = reader.readU16();
        int headerSize = reader.readU16();

        if (headerSize < BASE_HEADER_SIZE) {
            throw new RarParseException("RAR4 block at " + blockStart + " declares header size "
                    + headerSize + ", below the " + BASE_HEADER_SIZE + " byte minimum");
        }

        if (typeCode == TYPE_FILE || typeCode == TYPE_NEWSUB) {
            return readFileHeader(reader, blockStart, headerSize, flags, typeCode);
        }
        if (typeCode == TYPE_MAIN) {
            readMainHeader(flags);
        }
        long addSize = (flags & LONG_BLOCK) != 0 ? reader.readU32() : 0;
        return new RarGenericBlock(new RarBlockInfo(blockType(typeCode), blockStart, headerSize,
                blockStart + headerSize, addSize, flags));
    }

    private void readMainHeader(int flags) throws RarParseException {
        if ((flags & MAIN_ENCRYPTED_HEADERS) != 0) {
            throw new RarParseException("Archive has encrypted headers; cannot read entries without"
                    + " the password");
        }
        volumeInfo = new RarVolumeInfo((flags & MAIN_VOLUME) != 0, -1,
                (flags & MAIN_FIRST_VOLUME) != 0, (flags & MAIN_SOLID) != 0);
    }

    private RarFileEntry readFileHeader(RarHeaderReader reader, long blockStart, int headerSize,
                                        int headerFlags, int typeCode) throws IOException {
        long packedSize = reader.readU32();
        long unpackedSize = reader.readU32();
        int hostOs = reader.readU8();
        long crc32 = reader.readU32();
        long mtime = reader.readU32();
        reader.readU8(); // The minimum version of RAR that can decompress this file.
        int method = reader.readU8() - METHOD_STORE;
        int nameSize = reader.readU16();
        reader.readU32(); // The file attributes.

        if ((headerFlags & LARGE_FILE) != 0) {
            packedSize |= reader.readU32() << 32;
            unpackedSize |= reader.readU32() << 32;
        }

        long headerEnd = blockStart + headerSize;
        if (nameSize < 0 || nameSize > MAX_NAME_SIZE) {
            throw new RarParseException("RAR4 file header at " + blockStart + " declares name size "
                    + nameSize);
        }
        if (reader.position() + nameSize > headerEnd) {
            throw new RarParseException("RAR4 file header at " + blockStart + " has a name running"
                    + " past the end of its header");
        }
        String name = Rar4NameDecoder.decode(reader.readBytes(nameSize),
                (headerFlags & UNICODE_NAME) != 0);

        RarBlockInfo info = new RarBlockInfo(
                typeCode == TYPE_FILE ? RarBlockType.FILE : RarBlockType.SERVICE,
                blockStart, headerSize, headerEnd, packedSize, headerFlags);
        RarCompression compression = new RarCompression(method, (headerFlags & SOLID) != 0);
        RarFileFlags flags = new RarFileFlags(
                (headerFlags & DIRECTORY_MASK) == DIRECTORY_MASK,
                (headerFlags & ENCRYPTED) != 0,
                (headerFlags & SPLIT_BEFORE) != 0,
                (headerFlags & SPLIT_AFTER) != 0);

        return new RarFileEntry(info, name, unpackedSize, compression, flags, crc32, mtime, hostOs);
    }

    private RarBlockType blockType(int typeCode) {
        return switch (typeCode) {
            case TYPE_MAIN -> RarBlockType.MAIN;
            case TYPE_END -> RarBlockType.END;
            case TYPE_COMMENT -> RarBlockType.SUB;
            default -> RarBlockType.UNKNOWN;
        };
    }
}
