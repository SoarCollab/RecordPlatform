package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.dao.vo.attestation.AttestationBatchProductionRunVO;
import cn.flying.dao.vo.attestation.AttestationBatchProductionStatusVO;
import cn.flying.service.attestation.AttestationBatchProductionRunResult;
import cn.flying.service.attestation.AttestationBatchProductionService;
import cn.flying.service.attestation.AttestationBatchProductionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttestationBatchAdminControllerTest {

    private static final Long TENANT_ID = 7L;

    @Mock
    private AttestationBatchProductionService productionService;

    private AttestationBatchAdminController controller;

    /**
     * 初始化控制器和测试专用外部 ID 编码器。
     */
    @BeforeEach
    void setUp() {
        controller = new AttestationBatchAdminController(productionService);
        ReflectionTestUtils.setField(
                IdUtils.class,
                "secureIdCodec",
                new SecureIdCodec("SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234"));
    }

    /**
     * 验证人工入口固定 force 当前租户并把内部 batch ID 编码后返回。
     */
    @Test
    void triggerShouldForceCurrentTenantAndEncodeBatchIds() {
        when(productionService.runTenant(TENANT_ID, true)).thenReturn(
                new AttestationBatchProductionRunResult(
                        true, true, 2, 2, 0, 0, 1, 1, 0, 0, false, List.of(900L)));

        Result<AttestationBatchProductionRunVO> result = controller.trigger(TENANT_ID);

        assertThat(result.getData().force()).isTrue();
        assertThat(result.getData().batchIds()).singleElement().asString().isNotEqualTo("900");
        assertThat(IdUtils.fromExternalId(result.getData().batchIds().getFirst())).isEqualTo(900L);
        verify(productionService).runTenant(TENANT_ID, true);
    }

    /**
     * 验证状态入口只透传当前租户并保留有界配置和 backlog。
     */
    @Test
    void statusShouldReturnCurrentTenantBacklog() {
        Date oldest = new Date(1_700_000_000_000L);
        when(productionService.getStatus(TENANT_ID)).thenReturn(
                new AttestationBatchProductionStatus(
                        true, 50, 100, 600, 200, 2,
                        4, 1, 10, 2, oldest, 3));

        Result<AttestationBatchProductionStatusVO> result = controller.status(TENANT_ID);

        assertThat(result.getData().readyCandidates()).isEqualTo(4);
        assertThat(result.getData().dueBatches()).isEqualTo(3);
        assertThat(result.getData().oldestReadyAt()).isEqualTo(oldest);
        verify(productionService).getStatus(TENANT_ID);
    }

    /**
     * 验证状态响应在构造和读取边界都复制可变 Date。
     */
    @Test
    void statusVoShouldDefensivelyCopyOldestReadyAt() {
        Date source = new Date(1_700_000_000_000L);
        AttestationBatchProductionStatusVO status = new AttestationBatchProductionStatusVO(
                true, 50, 100, 600, 200, 2,
                4, 1, 10, 2, source, 3);

        source.setTime(0L);
        Date exposed = status.oldestReadyAt();
        exposed.setTime(1L);

        assertThat(status.oldestReadyAt()).isEqualTo(new Date(1_700_000_000_000L));
    }

    /**
     * 验证控制器具有管理员 RBAC，两个入口都有审计注解。
     */
    @Test
    void controllerShouldRequireAdminAndAuditEveryEndpoint() throws Exception {
        PreAuthorize preAuthorize = AttestationBatchAdminController.class.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("isAdmin()");

        for (Method method : List.of(
                AttestationBatchAdminController.class.getMethod("trigger", Long.class),
                AttestationBatchAdminController.class.getMethod("status", Long.class))) {
            assertThat(method.getAnnotation(OperationLog.class)).isNotNull();
        }
    }
}
