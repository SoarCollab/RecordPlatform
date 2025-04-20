package cn.flying.backendTest;


import cn.flying.common.annotation.SecureId;
import cn.flying.common.constant.Result;
import cn.flying.dao.vo.auth.AccountVO;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 用于测试SecureId注解的服务类
 * 这个类会被Spring管理，因此AOP切面会对它生效
 */
@Slf4j
@Service
public class TestSecureIdService {
    
    /**
     * 对传入的Result进行SecureId处理，明确设置hideOriginalId为true
     * @param result 包含AccountVO的Result对象
     * @return 处理后的Result对象
     */
    @SecureId(hideOriginalId = true)
    public Result<AccountVO> processWithSecureId(Result<AccountVO> result) {
        log.info("调用processWithSecureId方法，本应隐藏原始ID");
        return result;
    }
} 