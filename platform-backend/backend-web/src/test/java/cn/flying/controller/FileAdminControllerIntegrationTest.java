package cn.flying.controller;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.vo.admin.AdminFileDetailVO;
import cn.flying.dao.vo.admin.AdminFileQueryParam;
import cn.flying.dao.vo.admin.AdminFileVO;
import cn.flying.dao.vo.admin.AdminShareQueryParam;
import cn.flying.dao.vo.admin.AdminShareVO;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import cn.flying.dao.vo.file.ShareAccessStatsVO;
import cn.flying.service.FileAdminService;
import cn.flying.service.ShareAuditService;
import cn.flying.test.support.BaseControllerIntegrationTest;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FileAdminController 集成测试。
 *
 * <p>覆盖管理员鉴权、文件管理接口与分享管理接口的关键分支。</p>
 */
@Transactional
@DisplayName("FileAdminController Integration Tests")
@TestPropertySource(properties = "test.context=FileAdminControllerIntegrationTest")
class FileAdminControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/admin/files";
    private static final String TEST_FILE_ID = "EXT_FILE_1";
    private static final String TEST_SHARE_CODE = "SHARE001";

    @MockBean
    private FileAdminService fileAdminService;

    @MockBean
    private ShareAuditService shareAuditService;

    /**
     * 初始化默认管理员身份，便于大多数接口用例直接验证业务分支。
     */
    @BeforeEach
    void setUp() {
        setTestAdmin(100L, 1L);
    }

    @Nested
    @DisplayName("Auth Branches")
    class AuthBranches {

        /**
         * 验证未登录访问管理员接口会返回 401。
         */
        @Test
        @DisplayName("should return 401 when unauthenticated")
        void shouldReturn401WhenUnauthenticated() throws Exception {
            mockMvc.perform(get(BASE_URL).header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * 验证普通用户访问管理员接口会返回 403。
         */
        @Test
        @DisplayName("should return 403 when user is not admin")
        void shouldReturn403WhenUserIsNotAdmin() throws Exception {
            setTestUser(200L, 1L);

            performGet(BASE_URL)
                    .andExpect(status().isForbidden());
        }

        /**
         * 验证管理员访问管理员接口可成功返回业务结果。
         */
        @Test
        @DisplayName("should return 200 when admin requests file list")
        void shouldReturn200WhenAdminRequestsFileList() throws Exception {
            when(fileAdminService.getAllFiles(any(AdminFileQueryParam.class), any(Page.class)))
                    .thenReturn(buildFilePage());

            performGet(BASE_URL + "?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records[0].fileName").value("audit-file.pdf"));
        }
    }

    @Nested
    @DisplayName("File Management Endpoints")
    class FileManagementEndpoints {

        /**
         * 验证文件列表查询成功分支。
         */
        @Test
        @DisplayName("GET / should return paged files")
        void shouldReturnPagedFiles() throws Exception {
            when(fileAdminService.getAllFiles(any(AdminFileQueryParam.class), any(Page.class)))
                    .thenReturn(buildFilePage());

            performGet(BASE_URL + "?pageNum=1&pageSize=10&keyword=audit")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records[0].id").value(TEST_FILE_ID));
        }

        /**
         * 验证文件详情查询成功分支。
         */
        @Test
        @DisplayName("GET /{id} should return file detail")
        void shouldReturnFileDetail() throws Exception {
            when(fileAdminService.getFileDetail(TEST_FILE_ID)).thenReturn(buildFileDetail());

            performGet(BASE_URL + "/" + TEST_FILE_ID)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.fileHash").value("sha256-audit-1"));
        }

        /**
         * 验证文件详情查询的业务失败分支（文件不存在）。
         */
        @Test
        @DisplayName("GET /{id} should return business error when file missing")
        void shouldReturnBusinessErrorWhenFileMissing() throws Exception {
            when(fileAdminService.getFileDetail("MISSING_FILE"))
                    .thenThrow(new GeneralException(ResultEnum.PARAM_ERROR, "文件不存在"));

            performGet(BASE_URL + "/MISSING_FILE")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_ERROR.getCode()));
        }

        /**
         * 验证更新文件状态成功分支。
         */
        @Test
        @DisplayName("PUT /{id}/status should update status")
        void shouldUpdateFileStatus() throws Exception {
            Map<String, Object> body = Map.of("status", 2, "reason", "违规文件");

            performPut(BASE_URL + "/" + TEST_FILE_ID + "/status", body)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("文件状态已更新"));

            verify(fileAdminService).updateFileStatus(TEST_FILE_ID, 2, "违规文件");
        }

        /**
         * 验证强制删除文件成功分支。
         */
        @Test
        @DisplayName("DELETE /{id} should force delete file")
        void shouldForceDeleteFile() throws Exception {
            performDelete(BASE_URL + "/" + TEST_FILE_ID + "?reason=admin-clean")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("文件已删除"));

            verify(fileAdminService).forceDeleteFile(TEST_FILE_ID, "admin-clean");
        }

        /**
         * 验证强制删除文件业务失败分支（文件不存在）。
         */
        @Test
        @DisplayName("DELETE /{id} should return business error when file missing")
        void shouldReturnBusinessErrorWhenDeleteMissingFile() throws Exception {
            doThrow(new GeneralException(ResultEnum.PARAM_ERROR, "文件不存在"))
                    .when(fileAdminService).forceDeleteFile(anyString(), anyString());

            performDelete(BASE_URL + "/MISSING_FILE?reason=admin-clean")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_ERROR.getCode()));
        }
    }

    @Nested
    @DisplayName("Share Management Endpoints")
    class ShareManagementEndpoints {

        /**
         * 验证分享列表查询成功分支。
         */
        @Test
        @DisplayName("GET /shares should return paged shares")
        void shouldReturnPagedShares() throws Exception {
            when(fileAdminService.getAllShares(any(AdminShareQueryParam.class), any(Page.class)))
                    .thenReturn(buildSharePage());

            performGet(BASE_URL + "/shares?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records[0].shareCode").value(TEST_SHARE_CODE));
        }

        /**
         * 验证强制取消分享成功分支。
         */
        @Test
        @DisplayName("DELETE /shares/{shareCode} should cancel share")
        void shouldForceCancelShare() throws Exception {
            performDelete(BASE_URL + "/shares/" + TEST_SHARE_CODE + "?reason=risk-control")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("分享已取消"));

            verify(fileAdminService).forceCancelShare(TEST_SHARE_CODE, "risk-control");
        }

        /**
         * 验证强制取消分享业务失败分支（分享不存在）。
         */
        @Test
        @DisplayName("DELETE /shares/{shareCode} should return business error when share missing")
        void shouldReturnBusinessErrorWhenShareMissing() throws Exception {
            doThrow(new GeneralException(ResultEnum.PARAM_ERROR, "分享记录不存在"))
                    .when(fileAdminService).forceCancelShare(anyString(), anyString());

            performDelete(BASE_URL + "/shares/MISSING_SHARE?reason=risk-control")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_ERROR.getCode()));
        }

        /**
         * 验证分享访问日志查询成功分支。
         */
        @Test
        @DisplayName("GET /shares/{shareCode}/logs should return logs")
        void shouldReturnShareAccessLogs() throws Exception {
            when(shareAuditService.getShareAccessLogs(anyString(), any(Page.class)))
                    .thenReturn(buildShareLogPage());

            performGet(BASE_URL + "/shares/" + TEST_SHARE_CODE + "/logs?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records[0].shareCode").value(TEST_SHARE_CODE));
        }

        /**
         * 验证分享访问日志查询失败分支（分享不存在）。
         */
        @Test
        @DisplayName("GET /shares/{shareCode}/logs should return business error when share missing")
        void shouldReturnBusinessErrorWhenShareLogsMissing() throws Exception {
            when(shareAuditService.getShareAccessLogs(anyString(), any(Page.class)))
                    .thenThrow(new GeneralException(ResultEnum.PARAM_ERROR, "分享记录不存在"));

            performGet(BASE_URL + "/shares/MISSING_SHARE/logs?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_ERROR.getCode()));
        }

        /**
         * 验证分享访问统计查询成功分支。
         */
        @Test
        @DisplayName("GET /shares/{shareCode}/stats should return stats")
        void shouldReturnShareAccessStats() throws Exception {
            when(shareAuditService.getShareAccessStats(TEST_SHARE_CODE))
                    .thenReturn(new ShareAccessStatsVO(TEST_SHARE_CODE, 10L, 3L, 2L, 5L, 15L));

            performGet(BASE_URL + "/shares/" + TEST_SHARE_CODE + "/stats")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.totalAccess").value(15));
        }

        /**
         * 验证分享访问统计查询失败分支（分享不存在）。
         */
        @Test
        @DisplayName("GET /shares/{shareCode}/stats should return business error when share missing")
        void shouldReturnBusinessErrorWhenShareStatsMissing() throws Exception {
            when(shareAuditService.getShareAccessStats(anyString()))
                    .thenThrow(new GeneralException(ResultEnum.PARAM_ERROR, "分享记录不存在"));

            performGet(BASE_URL + "/shares/MISSING_SHARE/stats")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_ERROR.getCode()));
        }
    }

    /**
     * 构造文件分页响应，模拟管理员文件列表查询成功数据。
     */
    private IPage<AdminFileVO> buildFilePage() {
        Page<AdminFileVO> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(AdminFileVO.builder()
                .id(TEST_FILE_ID)
                .fileName("audit-file.pdf")
                .fileHash("sha256-audit-1")
                .status(1)
                .ownerName("admin-owner")
                .build()));
        return page;
    }

    /**
     * 构造文件详情响应，模拟管理员文件详情查询成功数据。
     */
    private AdminFileDetailVO buildFileDetail() {
        return AdminFileDetailVO.builder()
                .id(TEST_FILE_ID)
                .fileName("audit-file.pdf")
                .fileHash("sha256-audit-1")
                .ownerName("admin-owner")
                .status(1)
                .createTime(new Date())
                .build();
    }

    /**
     * 构造分享分页响应，模拟管理员分享列表查询成功数据。
     */
    private IPage<AdminShareVO> buildSharePage() {
        Page<AdminShareVO> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(AdminShareVO.builder()
                .id("1")
                .shareCode(TEST_SHARE_CODE)
                .sharerName("tester")
                .status(1)
                .hasPassword(false)
                .build()));
        return page;
    }

    /**
     * 构造访问日志分页响应，模拟管理员日志查询成功数据。
     */
    private IPage<ShareAccessLogVO> buildShareLogPage() {
        Page<ShareAccessLogVO> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(new ShareAccessLogVO(
                "1",
                TEST_SHARE_CODE,
                1,
                "查看",
                "100",
                "viewer",
                "127.0.0.1",
                "sha256-audit-1",
                "audit-file.pdf",
                new Date()
        )));
        return page;
    }
}
