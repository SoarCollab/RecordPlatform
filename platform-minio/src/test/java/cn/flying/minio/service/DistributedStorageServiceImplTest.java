package cn.flying.minio.service;

import cn.flying.minio.config.LogicNodeMapping;
import cn.flying.minio.config.MinioProperties;
import cn.flying.minio.core.MinioClientManager;
import cn.flying.minio.core.MinioMonitor;
import cn.flying.minio.core.S3ClientFactory;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.dto.MultipartUploadDTO;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DistributedStorageServiceImpl 测试类
 * 覆盖所有核心方法、异常处理和边界条件
 */
@ExtendWith(MockitoExtension.class)
class DistributedStorageServiceImplTest {

    @Mock
    private MinioClientManager clientManager;
    @Mock
    private MinioMonitor minioMonitor;
    @Mock
    private S3ClientFactory s3ClientFactory;
    @Mock
    private AmazonS3 s3Client;
    @Mock
    private MinioClient minioClient;

    private MinioProperties minioProperties;
    private DistributedStorageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DistributedStorageServiceImpl();
        minioProperties = new MinioProperties();

        ReflectionTestUtils.setField(service, "clientManager", clientManager);
        ReflectionTestUtils.setField(service, "minioMonitor", minioMonitor);
        ReflectionTestUtils.setField(service, "minioProperties", minioProperties);
        ReflectionTestUtils.setField(service, "s3ClientFactory", s3ClientFactory);

        lenient().when(minioMonitor.isNodeOnline(anyString())).thenReturn(true);
        lenient().when(minioMonitor.getNodeLoadScore(anyString())).thenReturn(0.5);
        lenient().when(s3ClientFactory.getS3Client(anyString())).thenReturn(s3Client);
        lenient().when(s3Client.doesBucketExistV2(anyString())).thenReturn(true);
    }

    // ========== 现有测试用例（保留） ==========

    @Test
    void storeFileReturnsSuccessWhenNodesOnline() throws Exception {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        MinioClient nodeAClient = mock(MinioClient.class);
        MinioClient nodeBClient = mock(MinioClient.class);

        when(clientManager.getClient("node-a")).thenReturn(nodeAClient);
        when(clientManager.getClient("node-b")).thenReturn(nodeBClient);
        when(nodeAClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(nodeBClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        List<byte[]> files = List.of("hello".getBytes(StandardCharsets.UTF_8));
        List<String> hashes = List.of("hash-1");

        Result<Map<String, String>> result = service.storeFile(files, hashes);

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals("minio/node/logic-1/hash-1", result.getData().get("hash-1"));

        verify(nodeAClient, atLeastOnce()).putObject(any(PutObjectArgs.class));
        verify(nodeBClient, atLeastOnce()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void storeFileReturnsErrorWhenInputEmpty() {
        Result<Map<String, String>> result = service.storeFile(new ArrayList<>(), new ArrayList<>());

        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
        assertNull(result.getData());
    }

    @Test
    void storeFileReturnsErrorWhenNoAvailableNodes() {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        when(minioMonitor.isNodeOnline(anyString())).thenReturn(false);

        Result<Map<String, String>> result = service.storeFile(
                List.of("hello".getBytes(StandardCharsets.UTF_8)),
                List.of("hash-1"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
        assertNull(result.getData());
    }

    @Test
    void initMultipartUploadStoresSession() {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(s3Client);
        when(s3Client.doesBucketExistV2("node-a")).thenReturn(true);

        InitiateMultipartUploadResult initResult = new InitiateMultipartUploadResult();
        initResult.setUploadId("upload-1");
        when(s3Client.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenReturn(initResult);

        Result<String> result = service.initMultipartUpload("file.txt", "hash-1", 10L, new HashMap<>(Map.of("k", "v")));

        assertTrue(result.isSuccess());
        assertEquals("upload-1", result.getData());

        Map<String, ?> sessions = getUploadSessions();
        assertTrue(sessions.containsKey("upload-1"));
    }

    @Test
    void uploadPartUpdatesSession() {
        initMultipartUploadStoresSession();

        UploadPartResult uploadPartResult = new UploadPartResult();
        uploadPartResult.setPartNumber(1);
        uploadPartResult.setETag("etag-1");
        when(s3Client.uploadPart(any())).thenReturn(uploadPartResult);

        Result<String> result = service.uploadPart("upload-1", 1, "abc".getBytes(StandardCharsets.UTF_8), "partHash");

        assertTrue(result.isSuccess());
        MultipartUploadDTO session = (MultipartUploadDTO) getUploadSessions().get("upload-1");
        assertNotNull(session);
        assertNotNull(session.getUploadedParts());
        assertEquals(1, session.getUploadedParts().size());
    }

    @Test
    void uploadPartWithoutSessionReturnsError() {
        Result<String> result = service.uploadPart("missing", 1, new byte[]{1}, "hash");
        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void completeMultipartUploadRemovesSession() {
        uploadPartUpdatesSession();

        CompleteMultipartUploadResult completeResult = new CompleteMultipartUploadResult();
        completeResult.setLocation("/bucket/object");
        when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).thenReturn(completeResult);
        when(s3ClientFactory.getS3Client("node-b"))
                .thenReturn(null); // skip async copy

        Result<String> result = service.completeMultipartUpload("upload-1", List.of("etag-1"));

        assertTrue(result.isSuccess());
        assertFalse(getUploadSessions().containsKey("upload-1"));
        assertEquals("minio/node/logic-1/hash-1", result.getData());
    }

    @Test
    void abortMultipartUploadCleansSession() {
        initMultipartUploadStoresSession();
        Result<Boolean> result = service.abortMultipartUpload("upload-1");
        assertTrue(result.isSuccess());
        assertFalse(getUploadSessions().containsKey("upload-1"));
        verify(s3Client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    void listUploadedPartsReturnsSortedParts() {
        uploadPartUpdatesSession();

        PartSummary summary = new PartSummary();
        summary.setPartNumber(1);
        summary.setETag("etag-1");
        summary.setSize(3L);
        summary.setLastModified(java.util.Date.from(Instant.now()));

        PartListing listing = new PartListing();
        listing.setParts(List.of(summary));
        listing.setTruncated(false);

        when(s3Client.listParts(any())).thenReturn(listing);

        Result<List<Map<String, Object>>> result = service.listUploadedParts("upload-1");
        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().size());
        assertEquals(1, result.getData().get(0).get("partNumber"));
    }

    @Test
    void storeFileStreamingUploadsToBothNodes() throws Exception {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        MinioClient nodeAClient = mock(MinioClient.class);
        MinioClient nodeBClient = mock(MinioClient.class);

        when(clientManager.getClient("node-a")).thenReturn(nodeAClient);
        when(clientManager.getClient("node-b")).thenReturn(nodeBClient);
        when(nodeAClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(nodeBClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);

        Result<Map<String, String>> result = service.storeFileStreaming(
                List.of(data), List.of("hash-1"), 1024);

        assertTrue(result.isSuccess());
        assertEquals("minio/node/logic-1/hash-1", result.getData().get("hash-1"));
        verify(nodeAClient, atLeastOnce()).putObject(any(PutObjectArgs.class));
        verify(nodeBClient, atLeastOnce()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void getFileUrlListByHashReturnsPresignedUrls() throws Exception {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        when(minioMonitor.getNodeLoadScore("node-a")).thenReturn(0.1);
        when(minioMonitor.getNodeLoadScore("node-b")).thenReturn(0.6);

        MinioClient primaryClient = mock(MinioClient.class);
        when(clientManager.getClient("node-a")).thenReturn(primaryClient);
        when(primaryClient.statObject(any(StatObjectArgs.class))).thenReturn(mock(StatObjectResponse.class));
        when(primaryClient.getPresignedObjectUrl(any())).thenReturn("https://example.com/object");

        String filePath = "minio/node/logic-1/hash-1";
        String fileHash = "hash-1";

        Result<List<String>> result = service.getFileUrlListByHash(List.of(filePath), List.of(fileHash));

        assertTrue(result.isSuccess());
        assertEquals(List.of("https://example.com/object"), result.getData());
    }

    @Test
    void getFileUrlListByHashReturnsErrorForMismatchedInput() {
        Result<List<String>> result = service.getFileUrlListByHash(
                List.of("minio/node/logic-1/hash-1"),
                List.of("hash-1", "hash-2"));

        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
        assertNull(result.getData());
    }

    @Test
    void getFileListByHashReturnsFileContent() throws Exception {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        when(minioMonitor.getNodeLoadScore("node-a")).thenReturn(0.1);
        when(minioMonitor.getNodeLoadScore("node-b")).thenReturn(0.6);

        byte[] payload = "content".getBytes(StandardCharsets.UTF_8);
        MinioClient primaryClient = mock(MinioClient.class);
        Headers headers = new Headers.Builder().build();
        GetObjectResponse response = new GetObjectResponse(headers, "bucket", "region", "object",
                new ByteArrayInputStream(payload));

        when(clientManager.getClient("node-a")).thenReturn(primaryClient);
        when(primaryClient.getObject(any(GetObjectArgs.class))).thenReturn(response);

        String filePath = "minio/node/logic-1/hash-1";
        String fileHash = "hash-1";

        Result<List<byte[]>> result = service.getFileListByHash(List.of(filePath), List.of(fileHash));

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        assertArrayEquals(payload, result.getData().get(0));
    }

    @Test
    void getFileUrlListByHashReturnsErrorWhenPathInvalid() {
        Result<List<String>> result = service.getFileUrlListByHash(
                List.of("invalid/path"),
                List.of("hash-1"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
        assertNotNull(result.getData());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    void getFileListByHashReturnsSuccessWhenInputEmpty() {
        Result<List<byte[]>> result = service.getFileListByHash(new ArrayList<>(), new ArrayList<>());

        assertTrue(result.isSuccess());
        assertNull(result.getData());
    }

    // ========== 新增测试用例 ==========

    // --- deleteFile 方法测试 ---
    @Test
    void deleteFileReturnsNull() {
        // 当前实现返回 null
        Map<String, String> fileContent = new HashMap<>();
        fileContent.put("file1", "path1");

        Result<Boolean> result = service.deleteFile(fileContent);
        assertNull(result);
    }

    // --- storeFile 方法异常处理测试 ---
    @Test
    void storeFileHandlesPartialFailure() throws Exception {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        MinioClient nodeAClient = mock(MinioClient.class);
        MinioClient nodeBClient = mock(MinioClient.class);

        when(clientManager.getClient("node-a")).thenReturn(nodeAClient);
        when(clientManager.getClient("node-b")).thenReturn(nodeBClient);
        when(nodeAClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        // 第二个节点抛出异常
        when(nodeBClient.bucketExists(any(BucketExistsArgs.class)))
            .thenThrow(new RuntimeException("Node B failure"));

        List<byte[]> files = List.of("hello".getBytes(StandardCharsets.UTF_8));
        List<String> hashes = List.of("hash-1");

        Result<Map<String, String>> result = service.storeFile(files, hashes);

        // 部分失败应返回错误
        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void storeFileWithNullLogicNodeMapping() {
        minioProperties.setLogicalMapping(null);

        Result<Map<String, String>> result = service.storeFile(
                List.of("hello".getBytes(StandardCharsets.UTF_8)),
                List.of("hash-1"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
        assertNull(result.getData());
    }

    @Test
    void storeFileWithInvalidPhysicalNodePair() {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a")); // 只有一个节点，不满足要求
        minioProperties.setLogicalMapping(List.of(mapping));

        Result<Map<String, String>> result = service.storeFile(
                List.of("hello".getBytes(StandardCharsets.UTF_8)),
                List.of("hash-1"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    // --- initMultipartUpload 方法异常测试 ---
    @Test
    void initMultipartUploadWithNullS3Client() {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(null);

        Result<String> result = service.initMultipartUpload("file.txt", "hash-1", 10L, null);

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
        assertNull(result.getData());
    }

    @Test
    void initMultipartUploadCreatesBucketWhenNotExists() {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(s3Client);
        when(s3Client.doesBucketExistV2("node-a")).thenReturn(false);

        InitiateMultipartUploadResult initResult = new InitiateMultipartUploadResult();
        initResult.setUploadId("upload-1");
        when(s3Client.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenReturn(initResult);

        Result<String> result = service.initMultipartUpload("file.txt", "hash-1", 10L, null);

        assertTrue(result.isSuccess());
        verify(s3Client).createBucket(anyString());
    }

    @Test
    void initMultipartUploadHandlesCreateBucketException() {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(s3Client);
        when(s3Client.doesBucketExistV2("node-a")).thenReturn(false);
        doThrow(new RuntimeException("Cannot create bucket"))
            .when(s3Client).createBucket(anyString());

        Result<String> result = service.initMultipartUpload("file.txt", "hash-1", 10L, null);

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void initMultipartUploadHandlesException() {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(s3Client);
        when(s3Client.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class)))
            .thenThrow(new RuntimeException("Init failed"));

        Result<String> result = service.initMultipartUpload("file.txt", "hash-1", 10L, null);

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    // --- uploadPart 方法异常测试 ---
    @Test
    void uploadPartWithNullS3Client() {
        MultipartUploadDTO session = MultipartUploadDTO.builder()
            .uploadId("upload-1")
            .nodeName("node-a")
            .bucketName("bucket")
            .objectName("object")
            .build();
        getUploadSessions().put("upload-1", session);

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(null);

        Result<String> result = service.uploadPart("upload-1", 1, new byte[]{1}, "hash");

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void uploadPartHandlesS3Exception() {
        MultipartUploadDTO session = MultipartUploadDTO.builder()
            .uploadId("upload-1")
            .nodeName("node-a")
            .bucketName("bucket")
            .objectName("object")
            .build();
        getUploadSessions().put("upload-1", session);

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(s3Client);
        when(s3Client.uploadPart(any())).thenThrow(new RuntimeException("Upload failed"));

        Result<String> result = service.uploadPart("upload-1", 1, new byte[]{1}, "hash");

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void uploadPartWithNullUploadedParts() {
        MultipartUploadDTO session = MultipartUploadDTO.builder()
            .uploadId("upload-1")
            .nodeName("node-a")
            .bucketName("bucket")
            .objectName("object")
            .uploadedParts(null)
            .build();
        getUploadSessions().put("upload-1", session);

        UploadPartResult uploadPartResult = new UploadPartResult();
        uploadPartResult.setPartNumber(1);
        uploadPartResult.setETag("etag-1");
        when(s3Client.uploadPart(any())).thenReturn(uploadPartResult);

        Result<String> result = service.uploadPart("upload-1", 1, "abc".getBytes(), "hash");

        assertTrue(result.isSuccess());
        assertNotNull(session.getUploadedParts());
        assertEquals(1, session.getUploadedParts().size());
    }

    // --- completeMultipartUpload 方法异常测试 ---
    @Test
    void completeMultipartUploadWithoutSession() {
        Result<String> result = service.completeMultipartUpload("missing-upload", List.of("etag"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
        assertNull(result.getData());
    }

    @Test
    void completeMultipartUploadWithNullS3Client() {
        MultipartUploadDTO session = MultipartUploadDTO.builder()
            .uploadId("upload-1")
            .nodeName("node-a")
            .logicNodeName("logic-1")
            .fileHash("hash-1")
            .bucketName("bucket")
            .objectName("object")
            .uploadedParts(new ArrayList<>())
            .build();
        getUploadSessions().put("upload-1", session);

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(null);

        Result<String> result = service.completeMultipartUpload("upload-1", List.of("etag"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void completeMultipartUploadWithEmptyParts() {
        MultipartUploadDTO session = MultipartUploadDTO.builder()
            .uploadId("upload-1")
            .nodeName("node-a")
            .logicNodeName("logic-1")
            .fileHash("hash-1")
            .bucketName("bucket")
            .objectName("object")
            .uploadedParts(new ArrayList<>())
            .build();
        getUploadSessions().put("upload-1", session);

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(s3Client);

        Result<String> result = service.completeMultipartUpload("upload-1", List.of("etag"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void completeMultipartUploadHandlesException() {
        MultipartUploadDTO session = MultipartUploadDTO.builder()
            .uploadId("upload-1")
            .nodeName("node-a")
            .logicNodeName("logic-1")
            .fileHash("hash-1")
            .bucketName("bucket")
            .objectName("object")
            .uploadedParts(List.of(Map.of("partNumber", 1, "etag", "etag-1")))
            .build();
        getUploadSessions().put("upload-1", session);

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(s3Client);
        when(s3Client.completeMultipartUpload(any()))
            .thenThrow(new RuntimeException("Complete failed"));

        Result<String> result = service.completeMultipartUpload("upload-1", List.of("etag-1"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    // --- abortMultipartUpload 方法测试 ---
    @Test
    void abortMultipartUploadWithoutSession() {
        Result<Boolean> result = service.abortMultipartUpload("missing-upload");

        assertTrue(result.isSuccess());
        assertTrue(result.getData());
    }

    @Test
    void abortMultipartUploadHandlesNoSuchUploadException() {
        MultipartUploadDTO session = MultipartUploadDTO.builder()
            .uploadId("upload-1")
            .nodeName("node-a")
            .bucketName("bucket")
            .objectName("object")
            .build();
        getUploadSessions().put("upload-1", session);

        AmazonS3Exception exception = mock(AmazonS3Exception.class);
        when(exception.getMessage()).thenReturn("NoSuchUpload");
        doThrow(exception).when(s3Client).abortMultipartUpload(any());

        Result<Boolean> result = service.abortMultipartUpload("upload-1");

        assertTrue(result.isSuccess());
        assertFalse(getUploadSessions().containsKey("upload-1"));
    }

    @Test
    void abortMultipartUploadHandlesGeneralException() {
        MultipartUploadDTO session = MultipartUploadDTO.builder()
            .uploadId("upload-1")
            .nodeName("node-a")
            .bucketName("bucket")
            .objectName("object")
            .build();
        getUploadSessions().put("upload-1", session);

        doThrow(new RuntimeException("Abort failed"))
            .when(s3Client).abortMultipartUpload(any());

        Result<Boolean> result = service.abortMultipartUpload("upload-1");

        assertTrue(result.isSuccess()); // 仍然返回成功并清理缓存
        assertFalse(getUploadSessions().containsKey("upload-1"));
    }

    // --- listUploadedParts 方法测试 ---
    @Test
    void listUploadedPartsWithoutSession() {
        Result<List<Map<String, Object>>> result = service.listUploadedParts("missing-upload");

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
        assertNull(result.getData());
    }

    @Test
    void listUploadedPartsWithNullS3Client() {
        MultipartUploadDTO session = MultipartUploadDTO.builder()
            .uploadId("upload-1")
            .nodeName("node-a")
            .bucketName("bucket")
            .objectName("object")
            .build();
        getUploadSessions().put("upload-1", session);

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(null);

        Result<List<Map<String, Object>>> result = service.listUploadedParts("upload-1");

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void listUploadedPartsWithTruncatedResults() {
        MultipartUploadDTO session = MultipartUploadDTO.builder()
            .uploadId("upload-1")
            .nodeName("node-a")
            .bucketName("bucket")
            .objectName("object")
            .build();
        getUploadSessions().put("upload-1", session);

        PartSummary part1 = new PartSummary();
        part1.setPartNumber(1);
        part1.setETag("etag-1");

        PartSummary part2 = new PartSummary();
        part2.setPartNumber(2);
        part2.setETag("etag-2");

        PartListing listing1 = new PartListing();
        listing1.setParts(List.of(part1));
        listing1.setTruncated(true);
        listing1.setNextPartNumberMarker(1);

        PartListing listing2 = new PartListing();
        listing2.setParts(List.of(part2));
        listing2.setTruncated(false);

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(s3Client);
        when(s3Client.listParts(any()))
            .thenReturn(listing1)
            .thenReturn(listing2);

        Result<List<Map<String, Object>>> result = service.listUploadedParts("upload-1");

        assertTrue(result.isSuccess());
        assertEquals(2, result.getData().size());
    }

    @Test
    void listUploadedPartsFallbackToCachedData() {
        Map<String, Object> cachedPart = new HashMap<>();
        cachedPart.put("partNumber", 1);
        cachedPart.put("etag", "cached-etag");

        MultipartUploadDTO session = MultipartUploadDTO.builder()
            .uploadId("upload-1")
            .nodeName("node-a")
            .bucketName("bucket")
            .objectName("object")
            .uploadedParts(List.of(cachedPart))
            .build();
        getUploadSessions().put("upload-1", session);

        when(s3ClientFactory.getS3Client("node-a")).thenReturn(s3Client);
        when(s3Client.listParts(any())).thenThrow(new RuntimeException("List failed"));

        Result<List<Map<String, Object>>> result = service.listUploadedParts("upload-1");

        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().size());
        assertEquals("cached-etag", result.getData().get(0).get("etag"));
    }

    // --- storeFileStreaming 方法测试 ---
    @Test
    void storeFileStreamingWithEmptyInput() {
        Result<Map<String, String>> result = service.storeFileStreaming(
            new ArrayList<>(), new ArrayList<>(), 1024);

        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
    }

    @Test
    void storeFileStreamingWithMismatchedSizes() {
        Result<Map<String, String>> result = service.storeFileStreaming(
            List.of(new byte[]{1}), List.of("hash-1", "hash-2"), 1024);

        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
    }

    @Test
    void storeFileStreamingWithNoAvailableNodes() {
        minioProperties.setLogicalMapping(new ArrayList<>());

        Result<Map<String, String>> result = service.storeFileStreaming(
            List.of(new byte[]{1}), List.of("hash-1"), 1024);

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void storeFileStreamingLargeFile() throws Exception {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        MinioClient nodeAClient = mock(MinioClient.class);
        when(clientManager.getClient("node-a")).thenReturn(nodeAClient);
        when(clientManager.getClient("node-b")).thenReturn(nodeAClient);
        when(nodeAClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        // 大文件（超过chunkSize）
        byte[] largeData = new byte[2048];
        Arrays.fill(largeData, (byte) 'X');

        Result<Map<String, String>> result = service.storeFileStreaming(
            List.of(largeData), List.of("hash-large"), 1024);

        assertTrue(result.isSuccess());
        verify(nodeAClient, atLeastOnce()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void storeFileStreamingHandlesException() throws Exception {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        when(clientManager.getClient("node-a")).thenReturn(null);

        Result<Map<String, String>> result = service.storeFileStreaming(
            List.of(new byte[]{1}), List.of("hash-1"), 1024);

        assertTrue(result.isSuccess());
        assertTrue(result.getData().isEmpty());
    }

    // --- getFileListByHash 方法异常测试 ---
    @Test
    void getFileListByHashHandlesException() throws Exception {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        MinioClient primaryClient = mock(MinioClient.class);
        when(clientManager.getClient("node-a")).thenReturn(primaryClient);
        when(primaryClient.getObject(any(GetObjectArgs.class)))
            .thenThrow(new RuntimeException("Get failed"));

        String filePath = "minio/node/logic-1/hash-1";
        String fileHash = "hash-1";

        Result<List<byte[]>> result = service.getFileListByHash(List.of(filePath), List.of(fileHash));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void getFileListByHashWithNoSuchKey() throws Exception {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        MinioClient primaryClient = mock(MinioClient.class);
        when(clientManager.getClient("node-a")).thenReturn(primaryClient);

        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn("NoSuchKey");
        ErrorResponseException exception = new ErrorResponseException(errorResponse, null, null);

        when(primaryClient.getObject(any(GetObjectArgs.class))).thenThrow(exception);
        when(clientManager.getClient("node-b")).thenReturn(null);

        String filePath = "minio/node/logic-1/hash-1";
        String fileHash = "hash-1";

        Result<List<byte[]>> result = service.getFileListByHash(List.of(filePath), List.of(fileHash));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    // --- getFileUrlListByHash 方法异常测试 ---
    @Test
    void getFileUrlListByHashWithEmptyInput() {
        Result<List<String>> result = service.getFileUrlListByHash(
            new ArrayList<>(), new ArrayList<>());

        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
    }

    @Test
    void getFileUrlListByHashWithInvalidLogicNode() {
        minioProperties.setLogicalMapping(new ArrayList<>());

        Result<List<String>> result = service.getFileUrlListByHash(
            List.of("minio/node/invalid-logic/hash-1"), List.of("hash-1"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void getFileUrlListByHashWithOfflineNodes() throws Exception {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        when(minioMonitor.isNodeOnline("node-a")).thenReturn(false);
        when(minioMonitor.isNodeOnline("node-b")).thenReturn(false);

        Result<List<String>> result = service.getFileUrlListByHash(
            List.of("minio/node/logic-1/hash-1"), List.of("hash-1"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    // --- 并发测试 ---
    @Test
    void storeFileHandlesConcurrentUploads() throws Exception {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        MinioClient nodeAClient = mock(MinioClient.class);
        MinioClient nodeBClient = mock(MinioClient.class);

        when(clientManager.getClient("node-a")).thenReturn(nodeAClient);
        when(clientManager.getClient("node-b")).thenReturn(nodeBClient);
        when(nodeAClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(nodeBClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        // 多个文件并发上传
        List<byte[]> files = Arrays.asList(
            "file1".getBytes(), "file2".getBytes(), "file3".getBytes());
        List<String> hashes = Arrays.asList("hash-1", "hash-2", "hash-3");

        Result<Map<String, String>> result = service.storeFile(files, hashes);

        assertTrue(result.isSuccess());
        assertEquals(3, result.getData().size());

        // 验证保持顺序
        List<String> keys = new ArrayList<>(result.getData().keySet());
        assertEquals(Arrays.asList("hash-1", "hash-2", "hash-3"), keys);
    }

    // --- 边界条件测试 ---
    @Test
    void testParseLogicalPathWithInvalidFormat() {
        Result<List<String>> result = service.getFileUrlListByHash(
            List.of("invalid-format"), List.of("hash"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void testParseLogicalPathWithMismatchedHash() {
        Result<List<String>> result = service.getFileUrlListByHash(
            List.of("minio/node/logic-1/different-hash"), List.of("hash-1"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    @Test
    void testSelectBestLogicNodeWithAllOffline() {
        LogicNodeMapping mapping = new LogicNodeMapping();
        mapping.setLogicNodeName("logic-1");
        mapping.setPhysicalNodePair(List.of("node-a", "node-b"));
        minioProperties.setLogicalMapping(List.of(mapping));

        when(minioMonitor.getNodeLoadScore("node-a")).thenReturn(Double.MAX_VALUE);
        when(minioMonitor.getNodeLoadScore("node-b")).thenReturn(Double.MAX_VALUE);
        when(minioMonitor.isNodeOnline("node-a")).thenReturn(false);
        when(minioMonitor.isNodeOnline("node-b")).thenReturn(false);

        Result<Map<String, String>> result = service.storeFile(
            List.of("test".getBytes()), List.of("hash"));

        assertEquals(ResultEnum.FILE_SERVICE_ERROR.getCode(), result.getCode());
    }

    // ========== 辅助方法 ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> getUploadSessions() {
        return (Map<String, Object>) ReflectionTestUtils.getField(service, "uploadSessions");
    }
}