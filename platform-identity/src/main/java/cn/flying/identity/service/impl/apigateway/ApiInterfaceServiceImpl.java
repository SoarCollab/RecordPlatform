package cn.flying.identity.service.impl.apigateway;

import cn.flying.identity.dto.apigateway.ApiInterface;
import cn.flying.identity.mapper.apigateway.ApiInterfaceMapper;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.apigateway.ApiInterfaceService;
import cn.flying.identity.util.IdUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * API接口管理服务实现类
 * 提供API接口的管理功能
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Service
public class ApiInterfaceServiceImpl extends BaseService implements ApiInterfaceService {

    @Resource
    private ApiInterfaceMapper interfaceMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiInterface createInterface(ApiInterface apiInterface) {
        requireNonNull(apiInterface, "接口信息不能为空");
        requireNonBlank(apiInterface.getInterfaceName(), "接口名称不能为空");
        requireNonBlank(apiInterface.getInterfacePath(), "接口路径不能为空");
        requireNonBlank(apiInterface.getInterfaceMethod(), "HTTP方法不能为空");

        if (apiInterface.getId() == null) {
            apiInterface.setId(IdUtils.nextEntityId());
        }

        if (apiInterface.getInterfaceStatus() == null) {
            apiInterface.setInterfaceStatus(0);
        }

        int inserted = interfaceMapper.insert(apiInterface);
        requireCondition(inserted, count -> count > 0, "创建接口失败");

        logInfo("创建API接口: id={}, name={}, path={}",
                apiInterface.getId(), apiInterface.getInterfaceName(), apiInterface.getInterfacePath());
        return apiInterface;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateInterface(ApiInterface apiInterface) {
        requireNonNull(apiInterface, "接口信息不能为空");
        requireNonNull(apiInterface.getId(), "接口ID不能为空");

        int updated = interfaceMapper.updateById(apiInterface);
        requireCondition(updated, count -> count > 0, "更新接口失败");

        logInfo("更新API接口: id={}", apiInterface.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteInterface(Long interfaceId) {
        requireNonNull(interfaceId, "接口ID不能为空");

        int deleted = interfaceMapper.deleteById(interfaceId);
        requireCondition(deleted, count -> count > 0, "删除接口失败");

        logInfo("删除API接口: id={}", interfaceId);
    }

    @Override
    public ApiInterface getInterfaceById(Long interfaceId) {
        requireNonNull(interfaceId, "接口ID不能为空");

        ApiInterface apiInterface = interfaceMapper.selectById(interfaceId);
        requireNonNull(apiInterface, "接口不存在");

        return apiInterface;
    }

    @Override
    public ApiInterface getInterfaceByPathAndMethod(String path, String method) {
        requireNonBlank(path, "接口路径不能为空");
        requireNonBlank(method, "HTTP方法不能为空");

        LambdaQueryWrapper<ApiInterface> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiInterface::getInterfacePath, path)
                .eq(ApiInterface::getInterfaceMethod, method)
                .eq(ApiInterface::getInterfaceStatus, 1);

        return interfaceMapper.selectOne(wrapper);
    }

    @Override
    public Page<ApiInterface> getInterfacesPage(int pageNum, int pageSize,
                                                String category, Integer status, String keyword) {
        requireCondition(pageNum, num -> num > 0, "页码必须大于0");
        requireCondition(pageSize, size -> size > 0 && size <= 100, "每页大小必须在1-100之间");

        Page<ApiInterface> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ApiInterface> wrapper = new LambdaQueryWrapper<>();

        if (isNotBlank(category)) {
            wrapper.eq(ApiInterface::getInterfaceCategory, category);
        }

        if (status != null) {
            wrapper.eq(ApiInterface::getInterfaceStatus, status);
        }

        if (isNotBlank(keyword)) {
            wrapper.and(w -> w.like(ApiInterface::getInterfaceName, keyword)
                    .or()
                    .like(ApiInterface::getInterfacePath, keyword));
        }

        wrapper.orderByDesc(ApiInterface::getCreateTime);

        return interfaceMapper.selectPage(page, wrapper);
    }

    @Override
    public List<ApiInterface> getOnlineInterfaces() {
        LambdaQueryWrapper<ApiInterface> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiInterface::getInterfaceStatus, 1)
                .orderByAsc(ApiInterface::getInterfaceCategory);

        return interfaceMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onlineInterface(Long interfaceId) {
        requireNonNull(interfaceId, "接口ID不能为空");

        ApiInterface apiInterface = new ApiInterface();
        apiInterface.setId(interfaceId);
        apiInterface.setInterfaceStatus(1);

        int updated = interfaceMapper.updateById(apiInterface);
        requireCondition(updated, count -> count > 0, "上线接口失败");

        logInfo("上线API接口: id={}", interfaceId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void offlineInterface(Long interfaceId, String reason) {
        requireNonNull(interfaceId, "接口ID不能为空");
        requireNonBlank(reason, "下线原因不能为空");

        ApiInterface apiInterface = new ApiInterface();
        apiInterface.setId(interfaceId);
        apiInterface.setInterfaceStatus(0);

        int updated = interfaceMapper.updateById(apiInterface);
        requireCondition(updated, count -> count > 0, "下线接口失败");

        logInfo("下线API接口: id={}, reason={}", interfaceId, reason);
    }
}
