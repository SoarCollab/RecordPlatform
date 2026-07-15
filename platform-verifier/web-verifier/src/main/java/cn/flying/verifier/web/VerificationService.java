package cn.flying.verifier.web;

import cn.flying.verifier.ProofVerifier;
import cn.flying.verifier.VerificationContext;
import cn.flying.verifier.contract.SignedProofBundleContract;
import cn.flying.verifier.model.VerificationReport;
import cn.flying.verifier.resolver.TrustedEvidenceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Streams multipart inputs to private temporary files and invokes the SDK under a concurrency bound.
 */
@Service
public final class VerificationService {

    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final ProofVerifier verifier;
    private final VerificationContextFactory contextFactory;
    private final VerifierProperties properties;
    private final Semaphore permits;

    /** Creates the bounded request service from immutable operator configuration. */
    public VerificationService(
            ProofVerifier verifier,
            VerificationContextFactory contextFactory,
            VerifierProperties properties
    ) {
        this.verifier = Objects.requireNonNull(verifier);
        this.contextFactory = Objects.requireNonNull(contextFactory);
        this.properties = Objects.requireNonNull(properties);
        this.permits = new Semaphore(properties.maxConcurrentVerifications(), true);
        requirePositive(properties.acquireTimeout(), "acquire timeout");
    }

    /** Verifies one original/proof pair and always removes private temporary bytes afterward. */
    public VerificationReport verify(
            MultipartFile original,
            MultipartFile proof,
            MultipartFile trustedKey
    ) {
        requirePart(original, "original");
        requirePart(proof, "proof");
        if (proof.isEmpty()) {
            throw badRequest("PROOF_REQUIRED", "The proof archive must not be empty");
        }
        acquirePermit();
        Path directory = null;
        try {
            directory = Files.createTempDirectory("record-platform-verifier-");
            Path originalPath = copyToTemp(
                    original, directory, "original-", properties.maxOriginalFileBytes(), "ORIGINAL_TOO_LARGE");
            Path proofPath = copyToTemp(
                    proof, directory, "proof-", SignedProofBundleContract.MAX_ARCHIVE_BYTES, "PROOF_TOO_LARGE");
            byte[] trustedKeyBytes = readOptionalBytes(
                    trustedKey, TrustedEvidenceLoader.MAX_TRUST_FILE_BYTES, "TRUSTED_KEY_TOO_LARGE");
            VerificationContext context;
            try {
                context = contextFactory.create(trustedKeyBytes);
            } catch (IllegalArgumentException e) {
                throw badRequest("TRUSTED_KEY_INVALID", "The trusted key JSON is invalid");
            }
            return verifier.verify(originalPath, proofPath, context);
        } catch (VerificationRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new VerificationRequestException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "TEMPORARY_STORAGE_ERROR",
                    "The verifier could not prepare isolated temporary storage");
        } finally {
            removeTemporaryDirectory(directory);
            permits.release();
        }
    }

    /** Rejects a missing multipart part while permitting a legitimate zero-byte original file. */
    private void requirePart(MultipartFile part, String name) {
        if (part == null) {
            throw badRequest("PART_REQUIRED", "Multipart part '" + name + "' is required");
        }
    }

    /** Acquires a fair verification slot without allowing an unbounded request queue. */
    private void acquirePermit() {
        try {
            if (!permits.tryAcquire(properties.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new VerificationRequestException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "VERIFIER_BUSY",
                        "The verifier is at its configured concurrency limit");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VerificationRequestException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "VERIFIER_INTERRUPTED",
                    "The verification request was interrupted before processing");
        }
    }

    /** Copies one multipart stream into an opaque server-created file with a hard byte limit. */
    private Path copyToTemp(
            MultipartFile part,
            Path directory,
            String prefix,
            long maxBytes,
            String limitCode
    ) throws IOException {
        rejectDeclaredOversize(part, maxBytes, limitCode);
        Path target = Files.createTempFile(directory, prefix, ".bin");
        try (InputStream input = part.getInputStream();
             OutputStream output = Files.newOutputStream(
                     target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            copyBounded(input, output, maxBytes, limitCode);
        }
        return target;
    }

    /** Reads a small optional trust anchor into bounded memory. */
    private byte[] readOptionalBytes(MultipartFile part, int maxBytes, String limitCode) throws IOException {
        if (part == null || part.isEmpty()) {
            return null;
        }
        rejectDeclaredOversize(part, maxBytes, limitCode);
        try (InputStream input = part.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyBounded(input, output, maxBytes, limitCode);
            return output.toByteArray();
        }
    }

    /** Uses both declared size and streaming enforcement to reject multipart limit bypasses. */
    private void rejectDeclaredOversize(MultipartFile part, long maxBytes, String limitCode) {
        if (part.getSize() > maxBytes) {
            throw new VerificationRequestException(
                    HttpStatus.PAYLOAD_TOO_LARGE, limitCode, "An uploaded part exceeds its configured byte limit");
        }
    }

    /** Copies until EOF while rejecting the first byte beyond the configured maximum. */
    private void copyBounded(
            InputStream input,
            OutputStream output,
            long maxBytes,
            String limitCode
    ) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total = Math.addExact(total, read);
            if (total > maxBytes) {
                throw new VerificationRequestException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        limitCode,
                        "An uploaded part exceeds its configured byte limit");
            }
            output.write(buffer, 0, read);
        }
    }

    /** Deletes only the server-created files and directory, ignoring cleanup failures after response creation. */
    private void removeTemporaryDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.list(directory)) {
            paths.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary cleanup is best effort after the verification result is fixed.
                }
            });
        } catch (IOException ignored) {
            // The directory may already be unavailable; no uploaded bytes are logged or returned.
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // The operating system temporary-directory policy provides the final cleanup fallback.
        }
    }

    /** Validates positive duration settings that Jakarta annotations cannot express portably. */
    private void requirePositive(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Verifier " + field + " must be positive");
        }
    }

    /** Creates a stable bad-request failure without exposing parser internals. */
    private VerificationRequestException badRequest(String code, String message) {
        return new VerificationRequestException(HttpStatus.BAD_REQUEST, code, message);
    }
}
