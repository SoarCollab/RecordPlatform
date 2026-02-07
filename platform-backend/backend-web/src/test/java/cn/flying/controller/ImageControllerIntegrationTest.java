package cn.flying.controller;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ImageController 集成测试
 *
 * 测试图片上传下载接口，包括安全验证
 */
@Transactional
@DisplayName("ImageController Integration Tests")
@TestPropertySource(properties = "test.context=ImageControllerIntegrationTest")
class ImageControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/images";

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Account testAccount;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
        testAccount = createTestAccount(testUserId, testTenantId, "imageuser");
    }

    private Account createTestAccount(Long userId, Long tenantId, String username) {
        Account account = new Account();
        account.setId(userId);
        account.setUsername(username + "_" + userId);
        account.setEmail(username + "_" + userId + "@test.com");
        account.setPassword(passwordEncoder.encode("password123"));
        account.setRole("user");
        account.setAvatar("https://example.com/avatar.png");
        account.setNickname("Test " + username);
        account.setTenantId(tenantId);
        account.setRegisterTime(new Date());
        account.setUpdateTime(new Date());
        account.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> accountMapper.insert(account));
        return account;
    }

    @Nested
    @DisplayName("POST /upload/avatar - Upload Avatar")
    class UploadAvatarTests {

        @Test
        @DisplayName("should upload avatar successfully")
        void shouldUploadAvatarSuccessfully() throws Exception {
            byte[] imageContent = createSmallImageContent();
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "avatar.jpg",
                    MediaType.IMAGE_JPEG_VALUE,
                    imageContent
            );

            mockMvc.perform(multipart(BASE_URL + "/upload/avatar")
                            .file(file)
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isString());
        }

        @Test
        @DisplayName("should fail for empty file")
        void shouldFailForEmptyFile() throws Exception {
            MockMultipartFile emptyFile = new MockMultipartFile(
                    "file",
                    "empty.jpg",
                    MediaType.IMAGE_JPEG_VALUE,
                    new byte[0]
            );

            mockMvc.perform(multipart(BASE_URL + "/upload/avatar")
                            .file(emptyFile)
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultEnum.FILE_EMPTY.getCode()));
        }

        @Test
        @DisplayName("should fail for file exceeding 100KB")
        void shouldFailForFileExceeding100KB() throws Exception {
            byte[] largeContent = new byte[101 * 1024]; // 101KB
            MockMultipartFile largeFile = new MockMultipartFile(
                    "file",
                    "large_avatar.jpg",
                    MediaType.IMAGE_JPEG_VALUE,
                    largeContent
            );

            mockMvc.perform(multipart(BASE_URL + "/upload/avatar")
                            .file(largeFile)
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40001))
                    .andExpect(jsonPath("$.message").value("图片文件过大，请上传小于100KB的图片！"));
        }

        @Test
        @DisplayName("should accept file at exactly 100KB")
        void shouldAcceptFileAtExactly100KB() throws Exception {
            byte[] content = new byte[100 * 1024]; // Exactly 100KB
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "exact_100kb.jpg",
                    MediaType.IMAGE_JPEG_VALUE,
                    content
            );

            mockMvc.perform(multipart(BASE_URL + "/upload/avatar")
                            .file(file)
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "avatar.jpg",
                    MediaType.IMAGE_JPEG_VALUE,
                    createSmallImageContent()
            );

            mockMvc.perform(multipart(BASE_URL + "/upload/avatar")
                            .file(file)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /upload/image - Upload Image")
    class UploadImageTests {

        @Test
        @DisplayName("should upload image successfully")
        void shouldUploadImageSuccessfully() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "image.jpg",
                    MediaType.IMAGE_JPEG_VALUE,
                    createSmallImageContent()
            );

            mockMvc.perform(multipart(BASE_URL + "/upload/image")
                            .file(file)
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isString());
        }

        @Test
        @DisplayName("should fail for empty file")
        void shouldFailForEmptyFile() throws Exception {
            MockMultipartFile emptyFile = new MockMultipartFile(
                    "file",
                    "empty.jpg",
                    MediaType.IMAGE_JPEG_VALUE,
                    new byte[0]
            );

            mockMvc.perform(multipart(BASE_URL + "/upload/image")
                            .file(emptyFile)
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultEnum.FILE_EMPTY.getCode()));
        }

        @Test
        @DisplayName("should fail for file exceeding 5MB")
        void shouldFailForFileExceeding5MB() throws Exception {
            byte[] largeContent = new byte[5 * 1024 * 1024 + 1]; // 5MB + 1 byte
            MockMultipartFile largeFile = new MockMultipartFile(
                    "file",
                    "large_image.jpg",
                    MediaType.IMAGE_JPEG_VALUE,
                    largeContent
            );

            mockMvc.perform(multipart(BASE_URL + "/upload/image")
                            .file(largeFile)
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultEnum.FILE_MAX_SIZE_OVERFLOW.getCode()));
        }
    }

    @Nested
    @DisplayName("GET /download/images/** - Download Image Security")
    class DownloadImageSecurityTests {

        @Test
        @DisplayName("should reject path traversal with ..")
        void shouldRejectPathTraversalWithDots() throws Exception {
            mockMvc.perform(get(BASE_URL + "/download/images/../../../etc/passwd")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should reject path traversal with //")
        void shouldRejectPathTraversalWithDoubleSlash() throws Exception {
            mockMvc.perform(get(BASE_URL + "/download/images//etc/passwd")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should reject path with backslash")
        void shouldRejectPathWithBackslash() throws Exception {
            mockMvc.perform(get(BASE_URL + "/download/images/\\etc\\passwd")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should reject Windows absolute path")
        void shouldRejectWindowsAbsolutePath() throws Exception {
            mockMvc.perform(get(BASE_URL + "/download/images/C:/Windows/System32")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 for path too short")
        void shouldReturn404ForPathTooShort() throws Exception {
            mockMvc.perform(get(BASE_URL + "/download/images/short")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should accept valid image path format")
        void shouldAcceptValidImagePathFormat() throws Exception {
            String imagePath = uploadAvatarAndGetPath();

            mockMvc.perform(get(BASE_URL + "/download/images" + imagePath)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_JPEG));
        }

        @Test
        @DisplayName("should reject encoded path traversal")
        void shouldRejectEncodedPathTraversal() throws Exception {
            // URL encoded ".." is "%2e%2e"
            mockMvc.perform(get(BASE_URL + "/download/images/%2e%2e/%2e%2e/etc/passwd")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should reject null byte injection")
        void shouldRejectNullByteInjection() throws Exception {
            mockMvc.perform(get(BASE_URL + "/download/images/avatar/test%00.jpg")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Authentication Tests")
    class AuthenticationTests {

        @Test
        @DisplayName("should allow unauthenticated download")
        void shouldAllowUnauthenticatedDownload() throws Exception {
            String imagePath = uploadAvatarAndGetPath();

            mockMvc.perform(get(BASE_URL + "/download/images" + imagePath)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_JPEG));
        }

        @Test
        @DisplayName("upload should require authentication")
        void uploadShouldRequireAuthentication() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "test.jpg",
                    MediaType.IMAGE_JPEG_VALUE,
                    createSmallImageContent()
            );

            mockMvc.perform(multipart(BASE_URL + "/upload/avatar")
                            .file(file)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("File Type Tests")
    class FileTypeTests {

        @Test
        @DisplayName("should accept PNG file")
        void shouldAcceptPngFile() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "image.png",
                    MediaType.IMAGE_PNG_VALUE,
                    createSmallImageContent()
            );

            mockMvc.perform(multipart(BASE_URL + "/upload/image")
                            .file(file)
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should accept GIF file")
        void shouldAcceptGifFile() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "image.gif",
                    MediaType.IMAGE_GIF_VALUE,
                    createSmallImageContent()
            );

            mockMvc.perform(multipart(BASE_URL + "/upload/image")
                            .file(file)
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    /**
     * 创建小型测试图片内容（有效的JPEG header）
     */
    private byte[] createSmallImageContent() {
        // Minimal JPEG file (1x1 pixel, about 100 bytes)
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
                0x01, 0x01, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xDB, 0x00, 0x43, 0x00, 0x08,
                // ... minimal JPEG data
                (byte) 0xFF, (byte) 0xD9
        };
    }

    /**
     * 上传一个小头像图片，并返回服务端生成的对象路径（如：/avatar/xxxx）。
     *
     * @return 图片对象路径
     */
    private String uploadAvatarAndGetPath() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.jpg",
                MediaType.IMAGE_JPEG_VALUE,
                createSmallImageContent()
        );

        MvcResult result = mockMvc.perform(multipart(BASE_URL + "/upload/avatar")
                        .file(file)
                        .header("Authorization", "Bearer " + testToken)
                        .header(HEADER_TENANT_ID, testTenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").asText();
    }
}
