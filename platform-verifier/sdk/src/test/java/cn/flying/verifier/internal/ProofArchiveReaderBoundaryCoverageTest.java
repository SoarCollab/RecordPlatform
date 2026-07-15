package cn.flying.verifier.internal;

import cn.flying.verifier.VerificationLimits;
import cn.flying.verifier.VerifierTestFixture;
import cn.flying.verifier.contract.SignedProofBundleContract;
import cn.flying.verifier.model.VerificationCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Exercises raw ZIP boundaries that must fail before signed proof content is trusted.
 */
class ProofArchiveReaderBoundaryCoverageTest {

    private static final int LOCAL_HEADER_BYTES = 30;
    private static final int CENTRAL_HEADER_BYTES = 46;
    private static final int EOCD_BYTES = 22;
    private static final int LOCAL_HEADER_SIGNATURE = 0x04034b50;
    private static final int EOCD_SIGNATURE = 0x06054b50;

    @TempDir
    Path directory;

    private ProofArchiveReader reader;
    private VerifierTestFixture.Fixture fixture;

    /** Creates one valid archive as the mutation baseline for each test. */
    @BeforeEach
    void setUp() throws Exception {
        reader = new ProofArchiveReader();
        fixture = new VerifierTestFixture().create(directory);
    }

    /** Rejects null, directory, empty, and structurally short archive inputs at the outer boundary. */
    @Test
    void shouldRejectUnsafeAndShortArchiveInputs() throws Exception {
        assertFailure(null, VerificationCode.ARCHIVE_MALFORMED);
        assertFailure(directory, VerificationCode.ARCHIVE_MALFORMED);

        Path empty = directory.resolve("empty.zip");
        Files.createFile(empty);
        assertFailure(empty, VerificationCode.ARCHIVE_MALFORMED);

        Path shortArchive = directory.resolve("short.zip");
        byte[] shortBytes = new byte[Integer.BYTES];
        writeInt(shortBytes, 0, LOCAL_HEADER_SIGNATURE);
        Files.write(shortArchive, shortBytes);
        assertFailure(shortArchive, VerificationCode.ARCHIVE_MALFORMED);
    }

    /** Converts an I/O parser failure after a valid-looking outer envelope into a stable malformed result. */
    @Test
    void shouldWrapZipParserFailureAfterEnvelopeValidation() throws Exception {
        byte[] malformed = new byte[60];
        writeInt(malformed, 0, LOCAL_HEADER_SIGNATURE);
        int eocdOffset = malformed.length - EOCD_BYTES;
        writeInt(malformed, eocdOffset, EOCD_SIGNATURE);
        writeShort(malformed, eocdOffset + 8, SignedProofBundleContract.ENTRY_ORDER.size());
        writeShort(malformed, eocdOffset + 10, SignedProofBundleContract.ENTRY_ORDER.size());
        writeInt(malformed, eocdOffset + 12, 1);
        writeInt(malformed, eocdOffset + 16, eocdOffset - 1);
        Path archive = directory.resolve("invalid-central-directory.zip");
        Files.write(archive, malformed);

        assertFailure(archive, VerificationCode.ARCHIVE_MALFORMED);
    }

    /** Rejects every unsupported EOCD disk, count, size, comment, and offset variant. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "disk-number",
            "central-disk",
            "disk-entry-count",
            "total-entry-count",
            "empty-central-directory",
            "zero-central-offset",
            "archive-comment",
            "directory-offset-mismatch"
    })
    void shouldRejectInvalidEndOfCentralDirectoryFields(String field) throws Exception {
        byte[] archive = Files.readAllBytes(fixture.proof());
        int eocdOffset = archive.length - EOCD_BYTES;
        switch (field) {
            case "disk-number" -> writeShort(archive, eocdOffset + 4, 1);
            case "central-disk" -> writeShort(archive, eocdOffset + 6, 1);
            case "disk-entry-count" -> writeShort(archive, eocdOffset + 8, 7);
            case "total-entry-count" -> writeShort(archive, eocdOffset + 10, 7);
            case "empty-central-directory" -> writeInt(archive, eocdOffset + 12, 0);
            case "zero-central-offset" -> writeInt(archive, eocdOffset + 16, 0);
            case "archive-comment" -> writeShort(archive, eocdOffset + 20, 1);
            case "directory-offset-mismatch" ->
                    writeInt(archive, eocdOffset + 16, readInt(archive, eocdOffset + 16) + 1);
            default -> throw new IllegalArgumentException("Unknown EOCD field: " + field);
        }
        Path malformed = directory.resolve("eocd-" + field + ".zip");
        Files.write(malformed, archive);

        assertFailure(malformed, VerificationCode.ARCHIVE_ENTRY_INVALID);
    }

    /** Rejects archives whose declared eight entries hide a ninth entry or a missing entry. */
    @Test
    void shouldRejectPhysicalEntryCountMismatchBehindDeclaredCount() throws Exception {
        LinkedHashMap<String, byte[]> extraEntries = fixture.mutableEntries();
        extraEntries.put("extra.txt", new byte[]{1});
        Path extraArchive = directory.resolve("hidden-extra-entry.zip");
        VerifierTestFixture.writeStoredArchive(extraArchive, extraEntries);
        forceDeclaredEntryCount(extraArchive, SignedProofBundleContract.ENTRY_ORDER.size());

        LinkedHashMap<String, byte[]> missingEntries = fixture.mutableEntries();
        missingEntries.remove(SignedProofBundleContract.README_ENTRY);
        Path missingArchive = directory.resolve("hidden-missing-entry.zip");
        VerifierTestFixture.writeStoredArchive(missingArchive, missingEntries);
        forceDeclaredEntryCount(missingArchive, SignedProofBundleContract.ENTRY_ORDER.size());

        assertFailure(extraArchive, VerificationCode.ARCHIVE_ENTRY_INVALID);
        assertFailure(missingArchive, VerificationCode.ARCHIVE_ENTRY_INVALID);
    }

    /** Rejects a physical gap inserted between the last entry payload and the central directory. */
    @Test
    void shouldRejectGapBeforeCentralDirectory() throws Exception {
        byte[] archive = Files.readAllBytes(fixture.proof());
        int oldEocdOffset = archive.length - EOCD_BYTES;
        int oldCentralOffset = readInt(archive, oldEocdOffset + 16);
        byte[] expanded = insertByte(archive, oldCentralOffset, (byte) 0);
        int newEocdOffset = oldEocdOffset + 1;
        writeInt(expanded, newEocdOffset + 16, oldCentralOffset + 1);
        Path malformed = directory.resolve("central-directory-gap.zip");
        Files.write(malformed, expanded);

        assertFailure(malformed, VerificationCode.ARCHIVE_ENTRY_INVALID);
    }

    /** Rejects a central-directory name that aliases the signed local entry name. */
    @Test
    void shouldRejectCentralDirectoryNameAlias() throws Exception {
        byte[] archive = Files.readAllBytes(fixture.proof());
        int eocdOffset = archive.length - EOCD_BYTES;
        int centralOffset = readInt(archive, eocdOffset + 16);
        archive[centralOffset + CENTRAL_HEADER_BYTES] ^= 1;
        Path malformed = directory.resolve("central-name-alias.zip");
        Files.write(malformed, archive);

        assertFailure(malformed, VerificationCode.ARCHIVE_ENTRY_INVALID);
    }

    /** Rejects an unsigned byte appended inside the declared central-directory range. */
    @Test
    void shouldRejectUnsignedCentralDirectoryByte() throws Exception {
        byte[] archive = Files.readAllBytes(fixture.proof());
        int oldEocdOffset = archive.length - EOCD_BYTES;
        int oldCentralSize = readInt(archive, oldEocdOffset + 12);
        byte[] expanded = insertByte(archive, oldEocdOffset, (byte) 0);
        int newEocdOffset = oldEocdOffset + 1;
        writeInt(expanded, newEocdOffset + 12, oldCentralSize + 1);
        Path malformed = directory.resolve("unsigned-central-byte.zip");
        Files.write(malformed, expanded);

        assertFailure(malformed, VerificationCode.ARCHIVE_ENTRY_INVALID);
    }

    /** Rejects CRC, size, and name-length drift in a non-leading local header. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"crc", "compressed-size", "size", "name-length", "extra-length"})
    void shouldRejectLocalHeaderMetadataDrift(String field) throws Exception {
        byte[] archive = Files.readAllBytes(fixture.proof());
        int secondLocalHeader = localHeaderOffset(archive, 1);
        switch (field) {
            case "crc" -> writeInt(archive, secondLocalHeader + 14,
                    readInt(archive, secondLocalHeader + 14) ^ 1);
            case "compressed-size" -> writeInt(archive, secondLocalHeader + 18,
                    readInt(archive, secondLocalHeader + 18) + 1);
            case "size" -> writeInt(archive, secondLocalHeader + 22,
                    readInt(archive, secondLocalHeader + 22) + 1);
            case "name-length" -> writeShort(archive, secondLocalHeader + 26,
                    readShort(archive, secondLocalHeader + 26) - 1);
            case "extra-length" -> writeShort(archive, secondLocalHeader + 28, 1);
            default -> throw new IllegalArgumentException("Unknown local-header field: " + field);
        }
        Path malformed = directory.resolve("local-" + field + ".zip");
        Files.write(malformed, archive);

        assertArchiveFailure(malformed);
    }

    /** Rejects payload corruption even when all signed ZIP header metadata remains unchanged. */
    @Test
    void shouldRejectStoredPayloadCrcMismatch() throws Exception {
        byte[] archive = Files.readAllBytes(fixture.proof());
        int firstPayloadOffset = LOCAL_HEADER_BYTES + readShort(archive, 26) + readShort(archive, 28);
        archive[firstPayloadOffset] ^= 1;
        Path malformed = directory.resolve("payload-crc-mismatch.zip");
        Files.write(malformed, archive);

        assertArchiveFailure(malformed);
    }

    /** Forces the EOCD count fields while retaining the physical central-directory bytes. */
    private void forceDeclaredEntryCount(Path archive, int count) throws Exception {
        byte[] bytes = Files.readAllBytes(archive);
        int eocdOffset = bytes.length - EOCD_BYTES;
        writeShort(bytes, eocdOffset + 8, count);
        writeShort(bytes, eocdOffset + 10, count);
        Files.write(archive, bytes);
    }

    /** Resolves one entry's local-header offset from the corresponding central-directory record. */
    private int localHeaderOffset(byte[] archive, int entryIndex) {
        int eocdOffset = archive.length - EOCD_BYTES;
        int centralOffset = readInt(archive, eocdOffset + 16);
        for (int index = 0; index < entryIndex; index++) {
            centralOffset += CENTRAL_HEADER_BYTES
                    + readShort(archive, centralOffset + 28)
                    + readShort(archive, centralOffset + 30)
                    + readShort(archive, centralOffset + 32);
        }
        return readInt(archive, centralOffset + 42);
    }

    /** Inserts one raw byte without interpreting the surrounding ZIP payload. */
    private byte[] insertByte(byte[] source, int offset, byte value) {
        byte[] result = new byte[source.length + 1];
        System.arraycopy(source, 0, result, 0, offset);
        result[offset] = value;
        System.arraycopy(source, offset, result, offset + 1, source.length - offset);
        return result;
    }

    /** Requires an exact stable archive failure code from the low-level reader. */
    private void assertFailure(Path archive, VerificationCode expectedCode) {
        ProofFormatException exception = catchThrowableOfType(
                ProofFormatException.class,
                () -> reader.read(archive, VerificationLimits.defaults()));
        assertThat(exception).isNotNull();
        assertThat(exception.code()).isEqualTo(expectedCode);
    }

    /** Requires malformed raw ZIP bytes to fail as either an entry-policy or parser error. */
    private void assertArchiveFailure(Path archive) {
        ProofFormatException exception = catchThrowableOfType(
                ProofFormatException.class,
                () -> reader.read(archive, VerificationLimits.defaults()));
        assertThat(exception).isNotNull();
        assertThat(exception.code()).isIn(
                VerificationCode.ARCHIVE_ENTRY_INVALID,
                VerificationCode.ARCHIVE_MALFORMED);
    }

    /** Reads one unsigned little-endian two-byte ZIP field. */
    private int readShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    /** Reads one signed-width little-endian four-byte ZIP field. */
    private int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    /** Writes one unsigned little-endian two-byte ZIP field. */
    private void writeShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    /** Writes one little-endian four-byte ZIP field. */
    private void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }
}
