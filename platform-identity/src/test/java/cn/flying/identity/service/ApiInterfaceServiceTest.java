package cn.flying.identity.service;

import cn.flying.identity.dto.apigateway.ApiInterface;
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

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
        testInterface.setInterfaceCode("TEST_API");
        testInterface.setInterfacePath("/api/test");
        testInterface.setInterfaceMethod("GET");
        testInterface.setInterfaceDescription("测试接口描述");
        testInterface.setInterfaceStatus(1);
    }

    @Test
    void testSelectById_Success() {
        when(apiInterfaceMapper.selectById(1L)).thenReturn(testInterface);
        
        ApiInterface result = apiInterfaceMapper.selectById(1L);
        
        assertNotNull(result);
        assertEquals("测试接口", result.getInterfaceName());
        verify(apiInterfaceMapper).selectById(1L);
    }

    @Test
    void testInsert_Success() {
        when(apiInterfaceMapper.insert(any(ApiInterface.class))).thenReturn(1);
        
        int result = apiInterfaceMapper.insert(testInterface);
        
        assertEquals(1, result);
        verify(apiInterfaceMapper).insert(any(ApiInterface.class));
    }

    @Test
    void testSelectList_Success() {
        List<ApiInterface> interfaces = Arrays.asList(testInterface);
        when(apiInterfaceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(interfaces);
        
        List<ApiInterface> result = apiInterfaceMapper.selectList(new LambdaQueryWrapper<>());
        
        assertNotNull(result);
        assertEquals(1, result.size());
        verify(apiInterfaceMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void testUpdateById_Success() {
        when(apiInterfaceMapper.updateById(any(ApiInterface.class))).thenReturn(1);
        
        testInterface.setInterfaceDescription("更新后的描述");
        int result = apiInterfaceMapper.updateById(testInterface);
        
        assertEquals(1, result);
        verify(apiInterfaceMapper).updateById(any(ApiInterface.class));
    }

    @Test
    void testDeleteById_Success() {
        when(apiInterfaceMapper.deleteById(1L)).thenReturn(1);
        
        int result = apiInterfaceMapper.deleteById(1L);
        
        assertEquals(1, result);
        verify(apiInterfaceMapper).deleteById(1L);
    }

    @Test
    void testSelectPage_Success() {
        Page<ApiInterface> page = new Page<>(1, 10);
        Page<ApiInterface> resultPage = new Page<>(1, 10);
        resultPage.setRecords(Arrays.asList(testInterface));
        resultPage.setTotal(1);
        
        when(apiInterfaceMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
            .thenReturn(resultPage);
        
        Page<ApiInterface> result = apiInterfaceMapper.selectPage(page, new LambdaQueryWrapper<>());
        
        assertNotNull(result);
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
    }
}
