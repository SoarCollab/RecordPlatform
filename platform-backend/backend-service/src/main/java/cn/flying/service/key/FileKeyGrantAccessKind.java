package cn.flying.service.key;

/**
 * 下载 grant 的闭集授权来源。
 */
public enum FileKeyGrantAccessKind {
    OWNER,
    ADMIN,
    FRIEND_SHARE,
    AUTHENTICATED_SHARE,
    PUBLIC_SHARE
}
