package cn.flying.identity.service;

import cn.flying.identity.dto.apigateway.ApiInterface;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.mapper.apigateway.ApiInterfaceMapper;
import cn.flying.identity.service.impl.apigateway.ApiInterfaceServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiInterfaceService 单元测试
 * 测试API接口管理服务
 *
 * @author 王贝强
 */
@ExtendWith(MockitoExtension.class)
class ApiInterfaceServiceTest {

    @Mock
    private ApiInterfaceMapper apiInterfaceMapper;

    @InjectMocks
    private ApiInterfaceServiceImpl apiInterfaceService;

    private ApiInterface testInterface;

    /**
     * 测试前准备数据
     */
    @BeforeEach
    void setUp() {
        testInterface = new ApiInterface();
        testInterface.setId(1L);
        testInterface.setInterfaceName("测试接口");
        testInterface.setInterfacePath("/api/test");
        testInterface.setInterfaceMethod("GET");
        testInterface.setInterfaceDescription("测试接口描述");
        testInterface.setInterfaceStatus(1);
    }

    @Test
    void createInterface_shouldPersistAndReturnEntity() {
        when(apiInterfaceMapper.insert(any(ApiInterface.class))).thenReturn(1);

        ApiInterface created = apiInterfaceService.createInterface(testInterface);

        assertSame(testInterface, created);
        verify(apiInterfaceMapper).insert(testInterface);
    }

    @Test
    void createInterface_shouldFailWhenMapperInsertReturnsZero() {
        when(apiInterfaceMapper.insert(any(ApiInterface.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> apiInterfaceService.createInterface(testInterface));
        assertEquals("创建接口失败", ex.getMessage());
        verify(apiInterfaceMapper).insert(testInterface);
    }

    @Test
    void updateInterface_shouldUpdateSuccessfully() {
        when(apiInterfaceMapper.updateById(any(ApiInterface.class))).thenReturn(1);

        assertDoesNotThrow(() -> apiInterfaceService.updateInterface(testInterface));
        verify(apiInterfaceMapper).updateById(testInterface);
    }

    @Test
    void updateInterface_shouldThrowWhenNoRecordUpdated() {
        when(apiInterfaceMapper.updateById(any(ApiInterface.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> apiInterfaceService.updateInterface(testInterface));
        assertEquals("更新接口失败", ex.getMessage());
    }

    @Test
    void deleteInterface_shouldRemoveRecord() {
        when(apiInterfaceMapper.deleteById(1L)).thenReturn(1);

        assertDoesNotThrow(() -> apiInterfaceService.deleteInterface(1L));
        verify(apiInterfaceMapper).deleteById(1L);
    }

    @Test
    void getInterfaceById_shouldReturnEntity() {
        when(apiInterfaceMapper.selectById(1L)).thenReturn(testInterface);

        ApiInterface result = apiInterfaceService.getInterfaceById(1L);

        assertNotNull(result);
        assertEquals("测试接口", result.getInterfaceName());
        verify(apiInterfaceMapper).selectById(1L);
    }

    @Test
    void getInterfaceById_shouldThrowWhenMissing() {
        when(apiInterfaceMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> apiInterfaceService.getInterfaceById(1L));
    }

    @Test
    void getInterfaceByPathAndMethod_shouldReturnOnlineInterface() {
        when(apiInterfaceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testInterface);

        ApiInterface result = apiInterfaceService.getInterfaceByPathAndMethod("/api/test", "GET");

        assertNotNull(result);
        assertEquals("/api/test", result.getInterfacePath());
        verify(apiInterfaceMapper).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void getInterfacesPage_shouldReturnPagedResult() {
        Page<ApiInterface> resultPage = new Page<>(1, 10);
        resultPage.setRecords(List.of(testInterface));
        resultPage.setTotal(1);

        when(apiInterfaceMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(resultPage);

        Page<ApiInterface> page = apiInterfaceService.getInterfacesPage(1, 10, null, null, null);

        assertEquals(1, page.getTotal());
        assertEquals(1, page.getRecords().size());
    }

    @Test
    void getOnlineInterfaces_shouldReturnList() {
        when(apiInterfaceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(testInterface));

        List<ApiInterface> result = apiInterfaceService.getOnlineInterfaces();

        assertEquals(1, result.size());
        verify(apiInterfaceMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void onlineInterface_shouldUpdateStatus() {
        when(apiInterfaceMapper.updateById(any(ApiInterface.class))).thenReturn(1);

        assertDoesNotThrow(() -> apiInterfaceService.onlineInterface(1L));
        verify(apiInterfaceMapper).updateById(any(ApiInterface.class));
    }

    @Test
    void onlineInterface_shouldThrowWhenUpdateFails() {
        when(apiInterfaceMapper.updateById(any(ApiInterface.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> apiInterfaceService.onlineInterface(1L));
    }

    @Test
    void offlineInterface_shouldUpdateStatusWithReason() {
        when(apiInterfaceMapper.updateById(any(ApiInterface.class))).thenReturn(1);

        assertDoesNotThrow(() -> apiInterfaceService.offlineInterface(1L, "弃用"));
        verify(apiInterfaceMapper).updateById(any(ApiInterface.class));
    }

    @Test
    void offlineInterface_shouldThrowWhenUpdateFails() {
        when(apiInterfaceMapper.updateById(any(ApiInterface.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> apiInterfaceService.offlineInterface(1L, "弃用"));
    }
}
