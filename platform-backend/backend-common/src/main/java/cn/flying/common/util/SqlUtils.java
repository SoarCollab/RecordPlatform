package cn.flying.common.util;

/**
 * SQL 工具类
 * 提供 SQL 查询相关的安全工具方法
 */
public final class SqlUtils {

    private SqlUtils() {
        // 工具类不允许实例化
    }

    /**
     * 转义 LIKE 查询中的特殊字符
     * 防止用户输入 % 或 _ 导致的通配符注入
     *
     * @param param 用户输入的查询参数
     * @return 转义后的参数，null 输入返回 null
     */
    public static String escapeLikeParameter(String param) {
        if (param == null) {
            return null;
        }
        return param
                .replace("\\", "\\\\")  // 先转义反斜杠
                .replace("%", "\\%")    // 转义百分号
                .replace("_", "\\_");   // 转义下划线
    }

    /**
     * 检查字符串是否包含 LIKE 通配符
     *
     * @param param 输入参数
     * @return 如果包含通配符返回 true
     */
    public static boolean containsWildcard(String param) {
        if (param == null) {
            return false;
        }
        return param.contains("%") || param.contains("_");
    }
}
