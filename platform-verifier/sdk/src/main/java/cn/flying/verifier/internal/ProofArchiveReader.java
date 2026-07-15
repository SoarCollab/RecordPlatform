package cn.flying.verifier.internal;

import cn.flying.verifier.VerificationLimits;
import cn.flying.verifier.contract.SignedProofBundleContract;
import cn.flying.verifier.model.VerificationCode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/**
 * Reads the frozen eight-entry STORED ZIP contract without extracting any path.
 */
public final class ProofArchiveReader {

    private static final int LOCAL_FILE_HEADER_BYTES = 30;
    private static final int CENTRAL_DIRECTORY_HEADER_BYTES = 46;
    private static final int END_OF_CENTRAL_DIRECTORY_BYTES = 22;
    private static final int LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;
    private static final int CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50;
    private static final int END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;
    private static final int STORED_ZIP_VERSION = 10;
    private static final int UTF8_NAME_FLAG = 1 << 11;
    private static final int FIXED_DOS_TIME = (SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME.getHour() << 11)
            | (SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME.getMinute() << 5)
            | (SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME.getSecond() / 2);
    private static final int FIXED_DOS_DATE = (
            (SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME.getYear() - 1980) << 9)
            | (SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME.getMonthValue() << 5)
            | SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME.getDayOfMonth();

    /**
     * Parses a bounded proof archive and validates central-directory metadata before returning bytes.
     *
     * @param archive proof ZIP path
     * @param limits verification limits
     * @return exact entry bytes in physical order
     */
    public ParsedProofArchive read(Path archive, VerificationLimits limits) {
        requireSafeArchiveFile(archive);
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(archive, options)) {
            ZipEnvelope envelope = validateOuterEnvelope(channel, limits);
            try (ZipFile zip = openZip(channel)) {
                Enumeration<ZipArchiveEntry> enumeration = zip.getEntriesInPhysicalOrder();
                List<ZipArchiveEntry> ordered = new ArrayList<>();
                while (enumeration.hasMoreElements()) {
                    ordered.add(enumeration.nextElement());
                    if (ordered.size() > SignedProofBundleContract.ENTRY_ORDER.size()) {
                        throw invalidEntry("Proof archive contains additional entries");
                    }
                }
                if (ordered.size() != SignedProofBundleContract.ENTRY_ORDER.size()) {
                    throw invalidEntry("Proof archive entry count is invalid");
                }
                validatePhysicalLayout(channel, zip, ordered, envelope);

                long total = 0L;
                Set<String> seenNames = new HashSet<>();
                LinkedHashMap<String, byte[]> bytesByName = new LinkedHashMap<>();
                for (int index = 0; index < ordered.size(); index++) {
                    ZipArchiveEntry entry = ordered.get(index);
                    String expectedName = SignedProofBundleContract.ENTRY_ORDER.get(index);
                    validateEntryMetadata(entry, expectedName, seenNames, limits);
                    byte[] bytes = readBounded(zip.getInputStream(entry), entry.getSize(), limits.maxEntryBytes());
                    validateCrc(entry, bytes);
                    total = addExact(total, bytes.length);
                    if (total > limits.maxTotalEntryBytes()) {
                        throw new ProofFormatException(
                                VerificationCode.ARCHIVE_TOO_LARGE,
                                "Proof archive logical payload exceeds the configured limit");
                    }
                    bytesByName.put(expectedName, bytes);
                }
                return new ParsedProofArchive(bytesByName);
            }
        } catch (ProofFormatException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ProofFormatException(
                    VerificationCode.ARCHIVE_MALFORMED,
                    "Proof archive cannot be parsed safely",
                    e);
        }
    }

    /** Validates that the outer path currently names a regular file without following a symbolic link. */
    private void requireSafeArchiveFile(Path archive) {
        if (archive == null
                || Files.isSymbolicLink(archive)
                || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new ProofFormatException(
                    VerificationCode.ARCHIVE_MALFORMED,
                    "Proof archive must be a regular non-symbolic-link file");
        }
    }

    /** Opens the already pinned no-follow channel after the raw envelope has been validated. */
    private ZipFile openZip(SeekableByteChannel channel) throws IOException {
        channel.position(0L);
        return ZipFile.builder().setSeekableByteChannel(channel).get();
    }

    /** Validates the fixed no-comment EOCD and rejects prepended, trailing, split, or ZIP64 envelopes. */
    private ZipEnvelope validateOuterEnvelope(
            SeekableByteChannel channel,
            VerificationLimits limits
    ) throws IOException {
        long size = channel.size();
        if (size <= 0) {
            throw new ProofFormatException(
                    VerificationCode.ARCHIVE_MALFORMED,
                    "Proof archive must not be empty");
        }
        if (size > limits.maxArchiveBytes()) {
            throw new ProofFormatException(
                    VerificationCode.ARCHIVE_TOO_LARGE,
                    "Proof archive byte size exceeds the configured limit");
        }
        if (size < LOCAL_FILE_HEADER_BYTES + END_OF_CENTRAL_DIRECTORY_BYTES) {
            throw new ProofFormatException(
                    VerificationCode.ARCHIVE_MALFORMED,
                    "Proof archive is too short for the fixed ZIP envelope");
        }

        ByteBuffer firstHeader = readExactly(channel, 0L, Integer.BYTES);
        if (firstHeader.getInt() != LOCAL_FILE_HEADER_SIGNATURE) {
            throw invalidEntry("Proof archive must start with the first local file header");
        }

        long eocdOffset = size - END_OF_CENTRAL_DIRECTORY_BYTES;
        ByteBuffer eocd = readExactly(channel, eocdOffset, END_OF_CENTRAL_DIRECTORY_BYTES);
        if (eocd.getInt() != END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
            throw invalidEntry("Proof archive must end with one comment-free central-directory record");
        }
        int diskNumber = Short.toUnsignedInt(eocd.getShort());
        int centralDirectoryDisk = Short.toUnsignedInt(eocd.getShort());
        int diskEntryCount = Short.toUnsignedInt(eocd.getShort());
        int totalEntryCount = Short.toUnsignedInt(eocd.getShort());
        long centralDirectorySize = Integer.toUnsignedLong(eocd.getInt());
        long centralDirectoryOffset = Integer.toUnsignedLong(eocd.getInt());
        int commentLength = Short.toUnsignedInt(eocd.getShort());
        boolean valid = diskNumber == 0
                && centralDirectoryDisk == 0
                && diskEntryCount == SignedProofBundleContract.ENTRY_ORDER.size()
                && totalEntryCount == SignedProofBundleContract.ENTRY_ORDER.size()
                && centralDirectorySize > 0
                && centralDirectoryOffset > 0
                && commentLength == 0
                && addExact(centralDirectoryOffset, centralDirectorySize) == eocdOffset;
        if (!valid) {
            throw invalidEntry("Proof archive central-directory envelope is invalid");
        }
        channel.position(0L);
        return new ZipEnvelope(centralDirectoryOffset, eocdOffset);
    }

    /** Reads one exact bounded raw ZIP range using little-endian field order. */
    private ByteBuffer readExactly(SeekableByteChannel channel, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(offset);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw invalidEntry("Proof archive metadata ended unexpectedly");
            }
        }
        return buffer.flip();
    }

    /** Rejects gaps, overlaps, comments, extra fields, alternate raw names, and data descriptors. */
    private void validatePhysicalLayout(
            SeekableByteChannel channel,
            ZipFile zip,
            List<ZipArchiveEntry> ordered,
            ZipEnvelope envelope
    ) throws IOException {
        if (zip.getFirstLocalFileHeaderOffset() != 0L) {
            throw invalidEntry("Proof archive contains bytes before its first entry");
        }
        validateRawCentralDirectory(channel, ordered, envelope);
        long expectedOffset = 0L;
        for (int index = 0; index < ordered.size(); index++) {
            ZipArchiveEntry entry = ordered.get(index);
            String expectedName = SignedProofBundleContract.ENTRY_ORDER.get(index);
            validateRawLocalHeader(channel, entry, expectedName);
            byte[] rawName = entry.getRawName();
            byte[] localExtra = entry.getLocalFileDataExtra();
            byte[] centralExtra = entry.getCentralDirectoryExtra();
            String comment = entry.getComment();
            long expectedDataOffset = addExact(
                    addExact(entry.getLocalHeaderOffset(), LOCAL_FILE_HEADER_BYTES),
                    addExact(rawName == null ? 0 : rawName.length, localExtra == null ? 0 : localExtra.length));
            if (entry.getLocalHeaderOffset() != expectedOffset
                    || entry.getDataOffset() != expectedDataOffset
                    || !entry.isStreamContiguous()
                    || entry.getDiskNumberStart() != 0
                    || entry.getGeneralPurposeBit().usesDataDescriptor()
                    || entry.getRawFlag() != UTF8_NAME_FLAG
                    || rawName == null
                    || !Arrays.equals(rawName, expectedName.getBytes(StandardCharsets.UTF_8))
                    || (localExtra != null && localExtra.length != 0)
                    || (centralExtra != null && centralExtra.length != 0)
                    || (comment != null && !comment.isEmpty())) {
                throw invalidEntry("Proof archive contains unsigned ZIP metadata, gaps, or overlaps");
            }
            expectedOffset = addExact(entry.getDataOffset(), entry.getCompressedSize());
        }
        if (expectedOffset != envelope.centralDirectoryOffset()) {
            throw invalidEntry("Proof archive contains bytes outside the fixed entry layout");
        }
        channel.position(0L);
    }

    /** Validates every unsigned central-directory field against the frozen v2 ZIP layout. */
    private void validateRawCentralDirectory(
            SeekableByteChannel channel,
            List<ZipArchiveEntry> ordered,
            ZipEnvelope envelope
    ) throws IOException {
        long offset = envelope.centralDirectoryOffset();
        for (int index = 0; index < ordered.size(); index++) {
            ZipArchiveEntry entry = ordered.get(index);
            byte[] expectedName = SignedProofBundleContract.ENTRY_ORDER.get(index)
                    .getBytes(StandardCharsets.UTF_8);
            ByteBuffer header = readExactly(channel, offset, CENTRAL_DIRECTORY_HEADER_BYTES);
            int signature = header.getInt();
            int versionMadeBy = Short.toUnsignedInt(header.getShort());
            int versionNeeded = Short.toUnsignedInt(header.getShort());
            int flags = Short.toUnsignedInt(header.getShort());
            int method = Short.toUnsignedInt(header.getShort());
            int modifiedTime = Short.toUnsignedInt(header.getShort());
            int modifiedDate = Short.toUnsignedInt(header.getShort());
            long crc = Integer.toUnsignedLong(header.getInt());
            long compressedSize = Integer.toUnsignedLong(header.getInt());
            long size = Integer.toUnsignedLong(header.getInt());
            int nameLength = Short.toUnsignedInt(header.getShort());
            int extraLength = Short.toUnsignedInt(header.getShort());
            int commentLength = Short.toUnsignedInt(header.getShort());
            int diskNumber = Short.toUnsignedInt(header.getShort());
            int internalAttributes = Short.toUnsignedInt(header.getShort());
            long externalAttributes = Integer.toUnsignedLong(header.getInt());
            long localHeaderOffset = Integer.toUnsignedLong(header.getInt());
            boolean valid = signature == CENTRAL_DIRECTORY_HEADER_SIGNATURE
                    && versionMadeBy == STORED_ZIP_VERSION
                    && versionNeeded == STORED_ZIP_VERSION
                    && flags == UTF8_NAME_FLAG
                    && method == ZipEntry.STORED
                    && modifiedTime == FIXED_DOS_TIME
                    && modifiedDate == FIXED_DOS_DATE
                    && crc == entry.getCrc()
                    && compressedSize == entry.getCompressedSize()
                    && size == entry.getSize()
                    && nameLength == expectedName.length
                    && extraLength == 0
                    && commentLength == 0
                    && diskNumber == 0
                    && internalAttributes == 0
                    && externalAttributes == 0
                    && localHeaderOffset == entry.getLocalHeaderOffset();
            if (!valid) {
                throw invalidEntry("Proof archive central-directory fields violate the fixed ZIP layout");
            }
            ByteBuffer rawName = readExactly(
                    channel,
                    addExact(offset, CENTRAL_DIRECTORY_HEADER_BYTES),
                    nameLength);
            if (!Arrays.equals(rawName.array(), expectedName)) {
                throw invalidEntry("Proof archive central-directory entry name is invalid");
            }
            offset = addExact(offset, CENTRAL_DIRECTORY_HEADER_BYTES + nameLength);
        }
        if (offset != envelope.centralDirectoryEnd()) {
            throw invalidEntry("Proof archive central directory contains unsigned bytes");
        }
    }

    /** Validates the raw local header instead of trusting central-directory aliases from a ZIP library. */
    private void validateRawLocalHeader(
            SeekableByteChannel channel,
            ZipArchiveEntry entry,
            String expectedName
    ) throws IOException {
        long offset = entry.getLocalHeaderOffset();
        byte[] expectedRawName = expectedName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer header = readExactly(channel, offset, LOCAL_FILE_HEADER_BYTES);
        int signature = header.getInt();
        int versionNeeded = Short.toUnsignedInt(header.getShort());
        int flags = Short.toUnsignedInt(header.getShort());
        int method = Short.toUnsignedInt(header.getShort());
        int modifiedTime = Short.toUnsignedInt(header.getShort());
        int modifiedDate = Short.toUnsignedInt(header.getShort());
        long crc = Integer.toUnsignedLong(header.getInt());
        long compressedSize = Integer.toUnsignedLong(header.getInt());
        long size = Integer.toUnsignedLong(header.getInt());
        int nameLength = Short.toUnsignedInt(header.getShort());
        int extraLength = Short.toUnsignedInt(header.getShort());
        boolean valid = signature == LOCAL_FILE_HEADER_SIGNATURE
                && versionNeeded == STORED_ZIP_VERSION
                && flags == UTF8_NAME_FLAG
                && method == ZipEntry.STORED
                && modifiedTime == FIXED_DOS_TIME
                && modifiedDate == FIXED_DOS_DATE
                && crc == entry.getCrc()
                && compressedSize == entry.getCompressedSize()
                && size == entry.getSize()
                && nameLength == expectedRawName.length
                && extraLength == 0;
        if (!valid) {
            throw invalidEntry("Proof archive local-header fields violate the fixed ZIP layout");
        }
        ByteBuffer rawName = readExactly(channel, addExact(offset, LOCAL_FILE_HEADER_BYTES), nameLength);
        if (!Arrays.equals(rawName.array(), expectedRawName)) {
            throw invalidEntry("Proof archive local-header entry name is invalid");
        }
    }

    /** Validates one entry name, order, link type, compression method, size, and timestamp. */
    private void validateEntryMetadata(
            ZipArchiveEntry entry,
            String expectedName,
            Set<String> seenNames,
            VerificationLimits limits
    ) {
        String name = entry == null ? null : entry.getName();
        if (entry == null
                || name == null
                || !expectedName.equals(name)
                || !seenNames.add(name)
                || name.startsWith("/")
                || name.contains("\\")
                || name.contains("/")
                || name.contains("..")
                || entry.isDirectory()
                || entry.isUnixSymlink()) {
            throw invalidEntry("Proof archive entry name, order, or type is invalid");
        }
        if (entry.getMethod() != ZipEntry.STORED
                || entry.getGeneralPurposeBit().usesEncryption()
                || entry.getGeneralPurposeBit().usesStrongEncryption()
                || entry.getSize() < 0
                || entry.getCompressedSize() < 0
                || entry.getSize() != entry.getCompressedSize()
                || entry.getSize() > limits.maxEntryBytes()
                || entry.getCrc() < 0
                || !SignedProofBundleContract.FIXED_ZIP_LOCAL_TIME.equals(entry.getTimeLocal())) {
            throw invalidEntry("Proof archive entry metadata violates the signed ZIP policy");
        }
    }

    /** Reads exactly the declared bounded entry size and rejects hidden trailing bytes. */
    private byte[] readBounded(InputStream input, long declaredSize, int maxEntryBytes) throws IOException {
        if (declaredSize < 0 || declaredSize > maxEntryBytes) {
            throw invalidEntry("Proof archive entry size is invalid");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.toIntExact(declaredSize));
        byte[] buffer = new byte[8192];
        long remaining = declaredSize;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw invalidEntry("Proof archive entry ended before its declared size");
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        if (input.read() != -1) {
            throw invalidEntry("Proof archive entry exceeds its declared size");
        }
        return output.toByteArray();
    }

    /** Recomputes CRC32 so corrupted STORED bytes fail closed. */
    private void validateCrc(ZipArchiveEntry entry, byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        if (crc.getValue() != entry.getCrc()) {
            throw invalidEntry("Proof archive entry CRC does not match its bytes");
        }
    }

    /** Adds logical entry sizes while converting overflow into a resource-limit failure. */
    private long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new ProofFormatException(
                    VerificationCode.RESOURCE_LIMIT_EXCEEDED,
                    "Proof archive logical size overflow",
                    e);
        }
    }

    /** Creates a stable archive-entry failure. */
    private ProofFormatException invalidEntry(String message) {
        return new ProofFormatException(VerificationCode.ARCHIVE_ENTRY_INVALID, message);
    }

    /** Raw fixed ZIP envelope fields needed after Commons Compress resolves local headers. */
    private record ZipEnvelope(long centralDirectoryOffset, long centralDirectoryEnd) {
    }
}
