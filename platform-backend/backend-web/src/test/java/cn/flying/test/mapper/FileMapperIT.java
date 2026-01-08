package cn.flying.test.mapper;

import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FileMapper Integration Tests")
class FileMapperIT extends BaseMapperIT {

    @Autowired
    private FileMapper fileMapper;

    private File createTestFile(Long userId, Long tenantId) {
        File file = new File();
        file.setId(IdUtils.nextEntityId());
        file.setUid(userId);
        file.setFileName("test-" + UUID.randomUUID() + ".pdf");
        file.setFileHash("sha256-" + UUID.randomUUID().toString().replace("-", ""));
        file.setFileParam("{\"fileSize\":1024,\"contentType\":\"application/pdf\"}");
        file.setClassification("document");
        file.setStatus(1);
        file.setTenantId(tenantId);
        file.setCreateTime(new Date());
        file.setDeleted(0);
        return file;
    }

    @Nested
    @DisplayName("Basic CRUD Operations")
    class BasicCrudTests {

        @Test
        @DisplayName("should insert and select file")
        void shouldInsertAndSelectFile() {
            File file = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            
            int inserted = fileMapper.insert(file);
            assertThat(inserted).isEqualTo(1);

            File found = fileMapper.selectById(file.getId());
            assertThat(found).isNotNull();
            assertThat(found.getFileName()).isEqualTo(file.getFileName());
            assertThat(found.getFileHash()).isEqualTo(file.getFileHash());
            assertThat(found.getUid()).isEqualTo(TEST_USER_ID);
        }

        @Test
        @DisplayName("should update file")
        void shouldUpdateFile() {
            File file = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            fileMapper.insert(file);

            file.setFileName("updated-name.pdf");
            file.setStatus(2);
            int updated = fileMapper.updateById(file);
            
            assertThat(updated).isEqualTo(1);
            
            File found = fileMapper.selectById(file.getId());
            assertThat(found.getFileName()).isEqualTo("updated-name.pdf");
            assertThat(found.getStatus()).isEqualTo(2);
        }

        @Test
        @DisplayName("should apply logical delete")
        void shouldApplyLogicalDelete() {
            File file = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            fileMapper.insert(file);

            int deleted = fileMapper.deleteById(file.getId());
            assertThat(deleted).isEqualTo(1);

            File found = fileMapper.selectById(file.getId());
            assertThat(found).isNull();
        }
    }

    @Nested
    @DisplayName("Custom Query Methods")
    class CustomQueryTests {

        @Test
        @DisplayName("countByUserId should return correct count")
        void countByUserIdShouldReturnCorrectCount() {
            File file1 = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            File file2 = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            File file3 = createTestFile(999L, TEST_TENANT_ID);
            fileMapper.insert(file1);
            fileMapper.insert(file2);
            fileMapper.insert(file3);

            Long count = fileMapper.countByUserId(TEST_USER_ID, TEST_TENANT_ID);
            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("countTodayUploadsByUserId should only count today's files")
        void countTodayUploadsShouldOnlyCountTodaysFiles() {
            File todayFile = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            todayFile.setCreateTime(new Date());
            fileMapper.insert(todayFile);

            File oldFile = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            oldFile.setCreateTime(new Date(System.currentTimeMillis() - 86400000L * 2));
            fileMapper.insert(oldFile);

            Date todayStart = java.sql.Date.valueOf(java.time.LocalDate.now());
            Long count = fileMapper.countTodayUploadsByUserId(TEST_USER_ID, TEST_TENANT_ID, todayStart);
            assertThat(count).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("countActiveFilesByHash should count files with same hash")
        void countActiveFilesByHashShouldCountFilesWithSameHash() {
            String sharedHash = "sha256-shared-" + UUID.randomUUID();
            
            File file1 = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            file1.setFileHash(sharedHash);
            fileMapper.insert(file1);

            File file2 = createTestFile(999L, TEST_TENANT_ID);
            file2.setFileHash(sharedHash);
            fileMapper.insert(file2);

            Long count = fileMapper.countActiveFilesByHash(sharedHash, file1.getId());
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("sumStorageByUserId should calculate total storage")
        void sumStorageByUserIdShouldCalculateTotalStorage() {
            File file1 = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            file1.setFileParam("{\"fileSize\":1000,\"contentType\":\"application/pdf\"}");
            fileMapper.insert(file1);

            File file2 = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            file2.setFileParam("{\"fileSize\":2000,\"contentType\":\"application/pdf\"}");
            fileMapper.insert(file2);

            Long totalStorage = fileMapper.sumStorageByUserId(TEST_USER_ID, TEST_TENANT_ID);
            assertThat(totalStorage).isEqualTo(3000L);
        }

        @Test
        @DisplayName("selectByIdIncludeDeleted should return deleted files")
        void selectByIdIncludeDeletedShouldReturnDeletedFiles() {
            File file = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            fileMapper.insert(file);
            fileMapper.deleteById(file.getId());

            File found = fileMapper.selectByIdIncludeDeleted(file.getId());
            assertThat(found).isNotNull();
            assertThat(found.getUid()).isEqualTo(TEST_USER_ID);
        }

        @Test
        @DisplayName("selectDeletedFilesForCleanup should return old deleted files")
        void selectDeletedFilesForCleanupShouldReturnOldDeletedFiles() {
            File file = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            file.setCreateTime(new Date(System.currentTimeMillis() - 86400000L * 31));
            fileMapper.insert(file);
            fileMapper.deleteById(file.getId());

            Date cutoffDate = new Date(System.currentTimeMillis() - 86400000L * 30);
            List<File> deletedFiles = fileMapper.selectDeletedFilesForCleanup(TEST_TENANT_ID, cutoffDate, 100);
            
            assertThat(deletedFiles).isNotEmpty();
            assertThat(deletedFiles.stream().anyMatch(f -> f.getId().equals(file.getId()))).isTrue();
        }

        @Test
        @DisplayName("physicalDeleteById should permanently delete file")
        void physicalDeleteByIdShouldPermanentlyDeleteFile() {
            File file = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            fileMapper.insert(file);

            int deleted = fileMapper.physicalDeleteById(file.getId(), TEST_TENANT_ID);
            assertThat(deleted).isEqualTo(1);

            File found = fileMapper.selectByIdIncludeDeleted(file.getId());
            assertThat(found).isNull();
        }
    }

    @Nested
    @DisplayName("Tenant Isolation Tests")
    class TenantIsolationTests {

        @Test
        @DisplayName("should isolate files by tenant")
        void shouldIsolateFilesByTenant() {
            File file1 = createTestFile(TEST_USER_ID, TEST_TENANT_ID);
            fileMapper.insert(file1);

            runWithTenant(999L, () -> {
                File file2 = createTestFile(TEST_USER_ID, 999L);
                fileMapper.insert(file2);
            });

            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(File::getUid, TEST_USER_ID);
            
            List<File> files = fileMapper.selectList(wrapper);
            assertThat(files).hasSize(1);
            assertThat(files.get(0).getTenantId()).isEqualTo(TEST_TENANT_ID);
        }
    }
}
