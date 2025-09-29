package cn.flying.identity.service.apigateway;

import cn.flying.identity.dto.apigateway.ApiInterface;
import cn.flying.platformapi.constant.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

/**
 * API接口管理服务接口
 * 提供API接口的管理功能
 *
 * @author 王贝强
 * @since 2025-10-11
 */
public interface ApiInterfaceService {

    /**
     * 创建API接口
     *
     * @param apiInterface 接口信息
     * @return 操作结果
     */
    Result<ApiInterface> createInterface(ApiInterface apiInterface);

    /**
     * 更新API接口
     *
     * @param apiInterface 接口信息
     * @return 操作结果
     */
    Result<Void> updateInterface(ApiInterface apiInterface);

    /**
     * 删除API接口
     *
     * @param interfaceId 接口ID
     * @return 操作结果
     */
    Result<Void> deleteInterface(Long interfaceId);

    /**
     * 根据ID查询接口
     *
     * @param interfaceId 接口ID
     * @return 接口信息
     */
    Result<ApiInterface> getInterfaceById(Long interfaceId);

    /**
     * 根据路径和方法查询接口
     *
     * @param path 接口路径
     * @param method HTTP方法
     * @return 接口信息
     */
    Result<ApiInterface> getInterfaceByPathAndMethod(String path, String method);

    /**
     * 分页查询接口列表
     *
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param category 分类（可选）
     * @param status 状态（可选）
     * @param keyword 关键词（可选）
     * @return 分页结果
     */
    Result<Page<ApiInterface>> getInterfacesPage(int pageNum, int pageSize,
                                                  String category, Integer status, String keyword);

    /**
     * 获取所有已上线的接口
     *
     * @return 接口列表
     */
    Result<List<ApiInterface>> getOnlineInterfaces();

    /**
     * 上线接口
     *
     * @param interfaceId 接口ID
     * @return 操作结果
     */
    Result<Void> onlineInterface(Long interfaceId);

    /**
     * 下线接口
     *
     * @param interfaceId 接口ID
     * @param reason 下线原因
     * @return 操作结果
     */
    Result<Void> offlineInterface(Long interfaceId, String reason);
}
