package cn.flying.test.builders;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.dao.dto.File;

import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Test data builder for File entity.
 * Provides fluent API for creating test fixtures.
 */
public class FileTestBuilder {

    private static final AtomicLong idCounter = new AtomicLong(1L);

    public static File aFile() {
        long id = idCounter.getAndIncrement();
        return new File()
                .setId(id)
                .setUid(100L)
                .setFileName("test_file.txt")
                .setFileHash("sha256_" + System.nanoTime())
                .setFileParam("{\"fileSize\":1024,\"contentType\":\"text/plain\",\"initialKey\":\"dGVzdGtleQ==\"}")
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setVersion(1)
                .setIsLatest(1)
                .setVersionGroupId(id)
                .setCreateTime(new Date());
    }

    public static File aFile(Consumer<File> customizer) {
        File file = aFile();
        customizer.accept(file);
        return file;
    }

    public static File aPendingFile() {
        return aFile(f -> f.setStatus(FileUploadStatus.PREPARE.getCode()));
    }

    public static File aFailedFile() {
        return aFile(f -> f.setStatus(FileUploadStatus.FAIL.getCode()));
    }

    public static File aFileForUser(Long userId) {
        return aFile(f -> f.setUid(userId));
    }

    public static File aFileWithHash(String hash) {
        return aFile(f -> f.setFileHash(hash));
    }

    public static File aFileWithName(String name) {
        return aFile(f -> f.setFileName(name));
    }

    public static File aVersionedFile(Long versionGroupId, int version, int isLatest) {
        return aFile(f -> f
                .setVersionGroupId(versionGroupId)
                .setVersion(version)
                .setIsLatest(isLatest));
    }

    public static File aNewVersion(File parent) {
        return aFile(f -> f
                .setUid(parent.getUid())
                .setTenantId(parent.getTenantId())
                .setVersionGroupId(parent.getVersionGroupId())
                .setVersion(parent.getVersion() + 1)
                .setParentVersionId(parent.getId())
                .setIsLatest(1)
                .setStatus(FileUploadStatus.PREPARE.getCode()));
    }

    public static void resetIdCounter() {
        idCounter.set(1L);
    }
}
