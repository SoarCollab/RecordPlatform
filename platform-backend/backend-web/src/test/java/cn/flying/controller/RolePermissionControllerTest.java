package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * RolePermissionController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RolePermissionControllerTest {

    @Mock
    private PermissionService permissionService;

    private RolePermissionController controller;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        controller = new RolePermissionController(permissionService);
    }

    /**
     * 验证角色授权与撤销 REST 新路径的成功分支。
     */
    @Test
    void shouldGrantAndRevokeRolePermission() {
        RolePermissionController.GrantPermissionVO vo = new RolePermissionController.GrantPermissionVO();
        vo.setPermissionCode("ticket:admin");

        doNothing().when(permissionService).assignPermissionToRole("admin", "ticket:admin", 0L);

        Result<String> grantResult = controller.grantRolePermission("admin", vo);
        assertEquals("授权成功", grantResult.getData());
        verify(permissionService).assignPermissionToRole("admin", "ticket:admin", 0L);

        doNothing().when(permissionService).revokePermissionFromRole("admin", "ticket:admin", 0L);

        Result<String> revokeResult = controller.revokeRolePermission("admin", "ticket:admin");
        assertEquals("撤销成功", revokeResult.getData());
        verify(permissionService).revokePermissionFromRole("admin", "ticket:admin", 0L);
    }

    /**
     * 验证授权时权限码不存在会抛出可追踪业务异常。
     */
    @Test
    void shouldThrowWhenPermissionCodeNotFound() {
        RolePermissionController.GrantPermissionVO vo = new RolePermissionController.GrantPermissionVO();
        vo.setPermissionCode("missing:perm");

        doThrow(new GeneralException(ResultEnum.RESULT_DATA_NONE, "权限码不存在: missing:perm"))
                .when(permissionService).assignPermissionToRole("admin", "missing:perm", 0L);

        GeneralException ex = assertThrows(GeneralException.class, () -> controller.grantRolePermission("admin", vo));
        assertEquals(ResultEnum.RESULT_DATA_NONE, ex.getResultEnum());
        verify(permissionService).assignPermissionToRole("admin", "missing:perm", 0L);
    }
}
