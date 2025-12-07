package cn.flying.service.filter;

import cn.flying.common.tenant.TenantContext;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.*;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Dubbo Filter for propagating MDC context and tenant context across RPC calls.
 * Ensures traceId, userId, reqId, tenantId are passed between consumer and provider.
 */
@Activate(group = {CommonConstants.CONSUMER, CommonConstants.PROVIDER}, order = -10000)
public class MdcDubboFilter implements Filter {

    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_SPAN_ID = "spanId";
    private static final String MDC_USER_ID = "userId";
    private static final String MDC_REQ_ID = "reqId";
    private static final String MDC_TENANT_ID = "tenantId";

    private static final String ATTACHMENT_PREFIX = "mdc.";
    private static final String TENANT_ATTACHMENT_KEY = "tenant.id";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        boolean isConsumer = RpcContext.getContext().isConsumerSide();

        if (isConsumer) {
            RpcContext clientContext = RpcContext.getClientAttachment();
            propagateMdcToAttachments(clientContext);
            propagateTenantToAttachments(clientContext);
        } else {
            RpcContext serverContext = RpcContext.getServerAttachment();
            restoreMdcFromAttachments(serverContext);
            restoreTenantFromAttachments(serverContext);
        }

        try {
            return invoker.invoke(invocation);
        } finally {
            if (!isConsumer) {
                clearMdc();
                TenantContext.clear();
            }
        }
    }

    /**
     * 消费者端：将 TenantContext 中的租户 ID 传播到 Dubbo attachments
     */
    private void propagateTenantToAttachments(RpcContext context) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            context.setAttachment(TENANT_ATTACHMENT_KEY, tenantId.toString());
        }
    }

    /**
     * 提供者端：从 Dubbo attachments 恢复租户 ID 到 TenantContext
     */
    private void restoreTenantFromAttachments(RpcContext context) {
        String tenantIdStr = context.getAttachment(TENANT_ATTACHMENT_KEY);
        if (tenantIdStr != null && !tenantIdStr.isEmpty()) {
            try {
                Long tenantId = Long.parseLong(tenantIdStr);
                TenantContext.setTenantId(tenantId);
                MDC.put(MDC_TENANT_ID, tenantIdStr);
            } catch (NumberFormatException e) {
                // 忽略无效的租户 ID
            }
        }
    }

    private void propagateMdcToAttachments(RpcContext context) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        if (mdcContext == null) {
            return;
        }

        setAttachmentIfPresent(context, mdcContext, MDC_TRACE_ID);
        setAttachmentIfPresent(context, mdcContext, MDC_SPAN_ID);
        setAttachmentIfPresent(context, mdcContext, MDC_USER_ID);
        setAttachmentIfPresent(context, mdcContext, MDC_REQ_ID);
        setAttachmentIfPresent(context, mdcContext, MDC_TENANT_ID);
    }

    private void setAttachmentIfPresent(RpcContext context, Map<String, String> mdcContext, String key) {
        String value = mdcContext.get(key);
        if (value != null && !value.isEmpty()) {
            context.setAttachment(ATTACHMENT_PREFIX + key, value);
        }
    }

    private void restoreMdcFromAttachments(RpcContext context) {
        restoreFromAttachment(context, MDC_TRACE_ID);
        restoreFromAttachment(context, MDC_SPAN_ID);
        restoreFromAttachment(context, MDC_USER_ID);
        restoreFromAttachment(context, MDC_REQ_ID);
        restoreFromAttachment(context, MDC_TENANT_ID);
    }

    private void restoreFromAttachment(RpcContext context, String key) {
        String value = context.getAttachment(ATTACHMENT_PREFIX + key);
        if (value != null && !value.isEmpty()) {
            MDC.put(key, value);
        }
    }

    private void clearMdc() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_REQ_ID);
        MDC.remove(MDC_TENANT_ID);
    }
}
