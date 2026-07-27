package cn.flying.service.key;

import cn.flying.dao.dto.File;

/**
 * 已完成业务授权后的 grant 创建上下文。
 */
public record FileKeyGrantIssueContext(
        File file,
        FileKeyGrantEnvelopeBinding envelopeBinding,
        FileKeyGrantAccessKind accessKind,
        Long actorId,
        String publicClientIdentity,
        String sessionId
) {

    /**
     * 返回当前 grant 是否属于匿名公开分享。
     */
    public boolean publicAccess() {
        return accessKind == FileKeyGrantAccessKind.PUBLIC_SHARE;
    }
}
