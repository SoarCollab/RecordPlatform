package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.entity.SysPermission;
import cn.flying.dao.entity.SysRolePermission;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import cn.flying.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RolePermissionController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RolePermissionControllerTest {

    @Mock
    private SysPermissionMapper permissionMapper;

    @Mock
    private SysRolePermissionMapper rolePermissionMapper;

    @Mock
    private PermissionService permissionService;

    private RolePermissionController controller;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        controller = new RolePermissionController();
        ReflectionTestUtils.setField(controller, "permissionMapper", permissionMapper);
        ReflectionTestUtils.setField(controller, "rolePermissionMapper", rolePermissionMapper);
        ReflectionTestUtils.setField(controller, "permissionService", permissionService);
    }

    /**
     * 验证角色授权与撤销 REST 新路径的成功分支。
     */
    @Test
    void shouldGrantAndRevokeRolePermission() {
        RolePermissionController.GrantPermissionVO vo = new RolePermissionController.GrantPermissionVO();
        vo.setPermissionCode("ticket:admin");

        SysPermission permission = new SysPermission();
        permission.setId(1L);

        when(permissionMapper.selectByCode("ticket:admin", 0L)).thenReturn(permission);
        when(rolePermissionMapper.countByRoleAndPermission("admin", "ticket:admin", 0L)).thenReturn(0);

        Result<String> grantResult = controller.grantRolePermission("admin", vo);
        assertEquals("授权成功", grantResult.getData());
        verify(rolePermissionMapper).insert(any(SysRolePermission.class));
        verify(permissionService).evictCache("admin", 0L);

        when(permissionMapper.selectByCode("ticket:admin", 0L)).thenReturn(permission);
        Result<String> revokeResult = controller.revokeRolePermission("admin", "ticket:admin");
        assertEquals("撤销成功", revokeResult.getData());
        verify(rolePermissionMapper).delete(any());
    }

    /**
     * 验证授权时权限码不存在会抛出可追踪业务异常。
     */
    @Test
    void shouldThrowWhenPermissionCodeNotFound() {
        RolePermissionController.GrantPermissionVO vo = new RolePermissionController.GrantPermissionVO();
        vo.setPermissionCode("missing:perm");

        when(permissionMapper.selectByCode("missing:perm", 0L)).thenReturn(null);

        GeneralException ex = assertThrows(GeneralException.class, () -> controller.grantRolePermission("admin", vo));
        assertEquals(ResultEnum.RESULT_DATA_NONE, ex.getResultEnum());
        verify(permissionMapper).selectByCode(eq("missing:perm"), eq(0L));
    }
}
