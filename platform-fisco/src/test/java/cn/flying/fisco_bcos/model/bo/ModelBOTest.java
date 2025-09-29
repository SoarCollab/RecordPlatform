package cn.flying.fisco_bcos.model.bo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ModelBOTest {

    @Test
    @DisplayName("Test SharingStoreFileInputBO")
    void testSharingStoreFileInputBO() {
        // Test constructor and toArgs
        String fileName = "test.txt";
        String uploader = "user123";
        String content = "file content";
        String param = "param1";

        SharingStoreFileInputBO bo = new SharingStoreFileInputBO(fileName, uploader, content, param);

        assertEquals(fileName, bo.getFileName());
        assertEquals(uploader, bo.getUploader());
        assertEquals(content, bo.getContent());
        assertEquals(param, bo.getParam());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(4, args.size());
        assertEquals(fileName, args.get(0));
        assertEquals(uploader, args.get(1));
        assertEquals(content, args.get(2));
        assertEquals(param, args.get(3));
    }

    @Test
    @DisplayName("Test StorageStoreFileInputBO")
    void testStorageStoreFileInputBO() {
        String fileName = "storage.txt";
        String uploader = "storageUser";
        String content = "storage content";
        String param = "storageParam";

        StorageStoreFileInputBO bo = new StorageStoreFileInputBO(fileName, uploader, content, param);

        assertEquals(fileName, bo.getFileName());
        assertEquals(uploader, bo.getUploader());
        assertEquals(content, bo.getContent());
        assertEquals(param, bo.getParam());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(4, args.size());
    }

    @Test
    @DisplayName("Test SharingDeleteFileInputBO")
    void testSharingDeleteFileInputBO() {
        String uploader = "deleteUser";
        byte[] fileHash = new byte[]{1, 2, 3, 4};

        SharingDeleteFileInputBO bo = new SharingDeleteFileInputBO(uploader, fileHash);

        assertEquals(uploader, bo.getUploader());
        assertArrayEquals(fileHash, bo.getFileHash());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(2, args.size());
        assertEquals(uploader, args.get(0));
        assertArrayEquals(fileHash, (byte[]) args.get(1));
    }

    @Test
    @DisplayName("Test StorageDeleteFileInputBO")
    void testStorageDeleteFileInputBO() {
        String uploader = "storageDeleteUser";
        byte[] fileHash = new byte[]{5, 6, 7, 8};

        StorageDeleteFileInputBO bo = new StorageDeleteFileInputBO(uploader, fileHash);

        assertEquals(uploader, bo.getUploader());
        assertArrayEquals(fileHash, bo.getFileHash());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(2, args.size());
    }

    @Test
    @DisplayName("Test SharingGetFileInputBO")
    void testSharingGetFileInputBO() {
        String uploader = "getFileUser";
        byte[] fileHash = new byte[]{9, 10, 11};

        SharingGetFileInputBO bo = new SharingGetFileInputBO(uploader, fileHash);

        assertEquals(uploader, bo.getUploader());
        assertArrayEquals(fileHash, bo.getFileHash());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(2, args.size());
    }

    @Test
    @DisplayName("Test StorageGetFileInputBO")
    void testStorageGetFileInputBO() {
        String uploader = "storageGetUser";
        byte[] fileHash = new byte[]{12, 13, 14};

        StorageGetFileInputBO bo = new StorageGetFileInputBO(uploader, fileHash);

        assertEquals(uploader, bo.getUploader());
        assertArrayEquals(fileHash, bo.getFileHash());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(2, args.size());
    }

    @Test
    @DisplayName("Test SharingGetUserFilesInputBO")
    void testSharingGetUserFilesInputBO() {
        String uploader = "getUserFilesUser";

        SharingGetUserFilesInputBO bo = new SharingGetUserFilesInputBO(uploader);

        assertEquals(uploader, bo.getUploader());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(1, args.size());
        assertEquals(uploader, args.getFirst());
    }

    @Test
    @DisplayName("Test StorageGetUserFilesInputBO")
    void testStorageGetUserFilesInputBO() {
        String uploader = "storageGetUserFilesUser";

        StorageGetUserFilesInputBO bo = new StorageGetUserFilesInputBO(uploader);

        assertEquals(uploader, bo.getUploader());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(1, args.size());
    }

    @Test
    @DisplayName("Test SharingDeleteFilesInputBO")
    void testSharingDeleteFilesInputBO() {
        String uploader = "deleteFilesUser";
        List<byte[]> fileHashList = Arrays.asList(
                new byte[]{1, 2, 3},
                new byte[]{4, 5, 6}
        );

        SharingDeleteFilesInputBO bo = new SharingDeleteFilesInputBO(uploader, fileHashList);

        assertEquals(uploader, bo.getUploader());
        assertEquals(fileHashList, bo.getFileHashes());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(2, args.size());
        assertEquals(uploader, args.get(0));
        assertEquals(fileHashList, args.get(1));
    }

    @Test
    @DisplayName("Test StorageDeleteFilesInputBO")
    void testStorageDeleteFilesInputBO() {
        String uploader = "storageDeleteFilesUser";
        List<byte[]> fileHashList = Arrays.asList(
                new byte[]{7, 8, 9},
                new byte[]{10, 11, 12}
        );

        StorageDeleteFilesInputBO bo = new StorageDeleteFilesInputBO(uploader, fileHashList);

        assertEquals(uploader, bo.getUploader());
        assertEquals(fileHashList, bo.getFileHashes());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(2, args.size());
    }

    @Test
    @DisplayName("Test SharingGetSharedFilesInputBO")
    void testSharingGetSharedFilesInputBO() {
        String shareCode = "SHARE123456";

        SharingGetSharedFilesInputBO bo = new SharingGetSharedFilesInputBO(shareCode);

        assertEquals(shareCode, bo.getShareCode());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(1, args.size());
        assertEquals(shareCode, args.getFirst());
    }

    @Test
    @DisplayName("Test SharingShareFilesInputBO")
    void testSharingShareFilesInputBO() {
        String uploader = "shareUser";
        List<byte[]> fileHashList = Arrays.asList(
                new byte[]{13, 14, 15},
                new byte[]{16, 17, 18}
        );
        Integer maxAccesses = 10;

        SharingShareFilesInputBO bo = new SharingShareFilesInputBO(uploader, fileHashList, maxAccesses);

        assertEquals(uploader, bo.getUploader());
        assertEquals(fileHashList, bo.getFileHashes());
        assertEquals(maxAccesses, bo.getMaxAccesses());

        List<Object> args = bo.toArgs();
        assertNotNull(args);
        assertEquals(3, args.size());
        assertEquals(uploader, args.get(0));
        assertEquals(fileHashList, args.get(1));
        assertEquals(maxAccesses, args.get(2));
    }

    @Test
    @DisplayName("Test model classes with null values")
    void testModelClassesWithNullValues() {
        // Test with null uploader
        SharingGetUserFilesInputBO bo1 = new SharingGetUserFilesInputBO(null);
        assertNull(bo1.getUploader());
        List<Object> args1 = bo1.toArgs();
        assertEquals(1, args1.size());
        assertNull(args1.getFirst());

        // Test with null shareCode
        SharingGetSharedFilesInputBO bo2 = new SharingGetSharedFilesInputBO(null);
        assertNull(bo2.getShareCode());
        List<Object> args2 = bo2.toArgs();
        assertEquals(1, args2.size());
        assertNull(args2.getFirst());

        // Test with null in constructor
        SharingStoreFileInputBO bo3 = new SharingStoreFileInputBO(null, null, null, null);
        assertNull(bo3.getFileName());
        assertNull(bo3.getUploader());
        assertNull(bo3.getContent());
        assertNull(bo3.getParam());
        List<Object> args3 = bo3.toArgs();
        assertEquals(4, args3.size());
        assertTrue(args3.stream().allMatch(Objects::isNull));
    }

    @Test
    @DisplayName("Test model classes with empty values")
    void testModelClassesWithEmptyValues() {
        // Test with empty string
        SharingGetUserFilesInputBO bo1 = new SharingGetUserFilesInputBO("");
        assertEquals("", bo1.getUploader());

        // Test with empty byte array
        byte[] emptyBytes = new byte[0];
        SharingDeleteFileInputBO bo2 = new SharingDeleteFileInputBO("user", emptyBytes);
        assertArrayEquals(emptyBytes, bo2.getFileHash());

        // Test with empty list
        List<byte[]> emptyList = List.of();
        SharingDeleteFilesInputBO bo3 = new SharingDeleteFilesInputBO("user", emptyList);
        assertTrue(bo3.getFileHashes().isEmpty());
    }
}
