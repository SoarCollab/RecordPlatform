package cn.flying.identity.rpc.filter;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Activate(group = CommonConstants.PROVIDER)
public class DubboProviderAuthFilter implements Filter {

    private static final String ATTACHMENT_TOKEN = "x-internal-token";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 读取期望令牌：优先系统属性，其次环境变量，最后使用配置默认值
        String expectedToken = System.getProperty("DUBBO_PROVIDER_TOKEN");
        if (expectedToken == null || expectedToken.isBlank()) {
            expectedToken = System.getenv("DUBBO_PROVIDER_TOKEN");
        }
        if (expectedToken == null || expectedToken.isBlank()) {
            expectedToken = "record-platform-internal-token";
        }

        // 从附件读取调用方令牌
        String providedToken = invocation.getAttachment(ATTACHMENT_TOKEN);
        if (providedToken == null) {
            providedToken = invocation.getAttachment("token");
        }

        // 校验内部令牌
        if (!expectedToken.equals(providedToken)) {
            log.warn("Dubbo provider auth failed: method={}.{}", invoker.getInterface().getSimpleName(), invocation.getMethodName());
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, "Unauthorized internal RPC");
        }

        // 可选：应用白名单（逗号分隔）
        String whitelist = System.getProperty("DUBBO_WHITELIST_APPS", System.getenv("DUBBO_WHITELIST_APPS"));
        if (whitelist != null && !whitelist.isBlank()) {
            Set<String> allowed = new HashSet<>();
            Arrays.stream(whitelist.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(allowed::add);
            String remoteApp = RpcContext.getServiceContext().getRemoteApplicationName();
            if (remoteApp == null || !allowed.contains(remoteApp)) {
                log.warn("Dubbo provider app not in whitelist: app={}", remoteApp);
                throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, "Application not allowed");
            }
        }

        return invoker.invoke(invocation);
    }
}
