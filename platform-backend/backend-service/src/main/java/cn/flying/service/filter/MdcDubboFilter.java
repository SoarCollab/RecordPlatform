package cn.flying.service.filter;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.*;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Dubbo Filter for propagating MDC context across RPC calls.
 * Ensures traceId, userId, reqId are passed between consumer and provider.
 */
@Activate(group = {CommonConstants.CONSUMER, CommonConstants.PROVIDER}, order = -10000)
public class MdcDubboFilter implements Filter {

    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_SPAN_ID = "spanId";
    private static final String MDC_USER_ID = "userId";
    private static final String MDC_REQ_ID = "reqId";

    private static final String ATTACHMENT_PREFIX = "mdc.";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        boolean isConsumer = RpcContext.getContext().isConsumerSide();

        if (isConsumer) {
            RpcContext clientContext = RpcContext.getClientAttachment();
            propagateMdcToAttachments(clientContext);
        } else {
            RpcContext serverContext = RpcContext.getServerAttachment();
            restoreMdcFromAttachments(serverContext);
        }

        try {
            return invoker.invoke(invocation);
        } finally {
            if (!isConsumer) {
                clearMdc();
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
    }
}
