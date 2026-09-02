package cn.flying.service.upload;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.vo.file.UploadFileTypePolicyVO;
import cn.flying.dao.vo.file.UploadPolicyVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the server-authoritative upload extension and MIME policy matrix.
 */
class FileUploadPolicyRegistryTest {

    /**
     * Verifies every published extension accepts its own aliases and browser fallback MIME values.
     */
    @Test
    void shouldAcceptEveryPublishedExtensionWithCompatibleOrGenericMime() {
        UploadPolicyVO policy = FileUploadPolicyRegistry.toView(4096L);

        assertEquals(4096L, policy.maxFileSizeBytes());
        assertTrue(policy.fileTypes().size() >= 70);
        for (UploadFileTypePolicyVO fileType : policy.fileTypes()) {
            String fileName = "sample." + fileType.extension().toUpperCase();
            assertFalse(fileType.mimeTypes().isEmpty());
            assertDoesNotThrow(() -> FileUploadPolicyRegistry.validate(fileName, fileType.mimeTypes().getFirst()));
            assertDoesNotThrow(() -> FileUploadPolicyRegistry.validate(fileName, ""));
            assertDoesNotThrow(() -> FileUploadPolicyRegistry.validate(fileName, null));
            assertDoesNotThrow(() -> FileUploadPolicyRegistry.validate(fileName, "application/octet-stream"));
        }
    }

    /**
     * Verifies MIME normalization removes case, whitespace, and parameters without widening compatibility.
     */
    @Test
    void shouldNormalizeMimeParametersAndRejectConcreteMismatch() {
        assertDoesNotThrow(() -> FileUploadPolicyRegistry.validate(
                "report.JSON", " Application/JSON ; charset=UTF-8"));

        GeneralException mismatch = assertThrows(GeneralException.class,
                () -> FileUploadPolicyRegistry.validate("report.pdf", "text/plain"));
        assertEquals(ResultEnum.FILE_ACCEPT_NOT_SUPPORT, mismatch.getResultEnum());
        assertTrue(String.valueOf(mismatch.getData()).contains(".pdf"));
        assertTrue(String.valueOf(mismatch.getData()).contains("text/plain"));
        assertTrue(String.valueOf(mismatch.getData()).contains("可上传类型"));
    }

    /**
     * Verifies malformed concrete MIME values fail without reflecting control characters.
     */
    @Test
    void shouldRejectMalformedMimeWithoutReflectingIt() {
        String malformedMime = "text/plain\nforged-log-line";

        GeneralException exception = assertThrows(GeneralException.class,
                () -> FileUploadPolicyRegistry.validate("notes.txt", malformedMime));

        assertEquals(ResultEnum.FILE_ACCEPT_NOT_SUPPORT, exception.getResultEnum());
        assertTrue(String.valueOf(exception.getData()).contains("内容类型格式无效"));
        assertFalse(String.valueOf(exception.getData()).contains(malformedMime));
    }

    /**
     * Verifies executable, installer, disk-image, absent, and missing extensions remain fail closed.
     */
    @Test
    void shouldRejectExcludedOrMissingExtensionsEvenWhenMimeLooksAllowed() {
        for (String fileName : List.of(
                "payload.exe", "library.dll", "installer.msi", "mobile.apk",
                "disk.iso", "virtual.qcow2", "archive.jar", "README")) {
            GeneralException exception = assertThrows(GeneralException.class,
                    () -> FileUploadPolicyRegistry.validate(fileName, "image/png"));
            assertEquals(ResultEnum.FILE_ACCEPT_NOT_SUPPORT, exception.getResultEnum());
            assertTrue(String.valueOf(exception.getData()).contains("可上传类型"));
        }
    }

    /**
     * Verifies active text formats are published only as escaped-text preview modes.
     */
    @Test
    void shouldPublishActiveFormatsAsTextOnly() {
        UploadPolicyVO policy = FileUploadPolicyRegistry.toView(1L);

        for (String extension : List.of("html", "htm", "svg", "js", "mjs", "cjs")) {
            UploadFileTypePolicyVO fileType = policy.fileTypes().stream()
                    .filter(candidate -> extension.equals(candidate.extension()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("text", fileType.previewMode());
        }
    }

    /**
     * Verifies empty browser MIME values are persisted as a stable generic binary MIME.
     */
    @Test
    void shouldNormalizeEmptyMimeForPersistence() {
        assertEquals("application/octet-stream", FileUploadPolicyRegistry.normalizeForPersistence(null));
        assertEquals("text/plain", FileUploadPolicyRegistry.normalizeForPersistence("Text/Plain; charset=utf-8"));
    }
}
