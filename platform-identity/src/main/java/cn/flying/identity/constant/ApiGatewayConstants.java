package cn.flying.identity.constant;

/**
 * API网关常量定义类
 * 统一管理API网关相关的所有常量、状态码和枚举值
 *
 * @author 王贝强
 * @since 2025-10-11
 */
public class ApiGatewayConstants {

    /**
     * 私有构造函数，防止实例化
     */
    private ApiGatewayConstants() {
        throw new IllegalStateException("常量类不能实例化");
    }

    /**
     * 应用状态枚举
     */
    public static final class AppStatus {
        /** 待审核 */
        public static final int PENDING = 0;
        /** 已启用 */
        public static final int ENABLED = 1;
        /** 已禁用 */
        public static final int DISABLED = 2;
        /** 已拒绝 */
        public static final int REJECTED = 3;

        /**
         * 获取状态描述
         * @param status 状态码
         * @return 状态描述
         */
        public static String getDescription(int status) {
            switch (status) {
                case PENDING: return "待审核";
                case ENABLED: return "已启用";
                case DISABLED: return "已禁用";
                case REJECTED: return "已拒绝";
                default: return "未知状态";
            }
        }

        /**
         * 验证状态码是否有效
         * @param status 状态码
         * @return 是否有效
         */
        public static boolean isValid(int status) {
            return status >= PENDING && status <= REJECTED;
        }
    }

    /**
     * 应用类型枚举
     */
    public static final class AppType {
        /** Web应用 */
        public static final int WEB = 1;
        /** 移动应用 */
        public static final int MOBILE = 2;
        /** 服务端应用 */
        public static final int SERVER = 3;
        /** 其他应用 */
        public static final int OTHER = 4;

        /**
         * 获取类型描述
         * @param type 类型码
         * @return 类型描述
         */
        public static String getDescription(int type) {
            switch (type) {
                case WEB: return "Web应用";
                case MOBILE: return "移动应用";
                case SERVER: return "服务端应用";
                case OTHER: return "其他应用";
                default: return "未知类型";
            }
        }

        /**
         * 验证类型码是否有效
         * @param type 类型码
         * @return 是否有效
         */
        public static boolean isValid(int type) {
            return type >= WEB && type <= OTHER;
        }
    }

    /**
     * API密钥状态枚举
     */
    public static final class KeyStatus {
        /** 已禁用 */
        public static final int DISABLED = 0;
        /** 已启用 */
        public static final int ENABLED = 1;
        /** 已过期 */
        public static final int EXPIRED = 2;

        /**
         * 获取状态描述
         * @param status 状态码
         * @return 状态描述
         */
        public static String getDescription(int status) {
            switch (status) {
                case DISABLED: return "已禁用";
                case ENABLED: return "已启用";
                case EXPIRED: return "已过期";
                default: return "未知状态";
            }
        }

        /**
         * 验证状态码是否有效
         * @param status 状态码
         * @return 是否有效
         */
        public static boolean isValid(int status) {
            return status >= DISABLED && status <= EXPIRED;
        }
    }

    /**
     * API密钥类型枚举
     */
    public static final class KeyType {
        /** 正式环境 */
        public static final int PRODUCTION = 1;
        /** 测试环境 */
        public static final int TEST = 2;

        /**
         * 获取类型描述
         * @param type 类型码
         * @return 类型描述
         */
        public static String getDescription(int type) {
            switch (type) {
                case PRODUCTION: return "正式环境";
                case TEST: return "测试环境";
                default: return "未知环境";
            }
        }

        /**
         * 验证类型码是否有效
         * @param type 类型码
         * @return 是否有效
         */
        public static boolean isValid(int type) {
            return type == PRODUCTION || type == TEST;
        }
    }

    /**
     * Redis缓存键前缀
     */
    public static final class RedisKey {
        /** 应用缓存前缀 */
        public static final String APP_PREFIX = "api:app:";
        /** 应用统计前缀 */
        public static final String APP_STATS_PREFIX = "api:app:stats:";
        /** API密钥缓存前缀 */
        public static final String KEY_PREFIX = "api:key:";
        /** Nonce缓存前缀 */
        public static final String NONCE_PREFIX = "api:nonce:";
        /** 权限缓存前缀 */
        public static final String PERMISSION_PREFIX = "api:permission:";
        /** 接口缓存前缀 */
        public static final String INTERFACE_PREFIX = "api:interface:";
        /** 限流缓存前缀 */
        public static final String RATE_LIMIT_PREFIX = "api:rate:";
        /** 黑名单前缀 */
        public static final String BLACKLIST_PREFIX = "api:blacklist:";
        /** 白名单前缀 */
        public static final String WHITELIST_PREFIX = "api:whitelist:";
    }

    /**
     * 时间常量（秒）
     */
    public static final class TimeConstants {
        /** 1分钟 */
        public static final int ONE_MINUTE = 60;
        /** 5分钟 */
        public static final int FIVE_MINUTES = 300;
        /** 10分钟 */
        public static final int TEN_MINUTES = 600;
        /** 30分钟 */
        public static final int THIRTY_MINUTES = 1800;
        /** 1小时 */
        public static final int ONE_HOUR = 3600;
        /** 2小时 */
        public static final int TWO_HOURS = 7200;
        /** 6小时 */
        public static final int SIX_HOURS = 21600;
        /** 12小时 */
        public static final int TWELVE_HOURS = 43200;
        /** 24小时 */
        public static final int ONE_DAY = 86400;
        /** 7天 */
        public static final int SEVEN_DAYS = 604800;
        /** 30天 */
        public static final int THIRTY_DAYS = 2592000;
    }

    /**
     * 系统配置常量
     */
    public static final class SystemConfig {
        /** 最大重试次数 */
        public static final int MAX_RETRY_TIMES = 3;
        /** 应用标识码生成最大重试次数 */
        public static final int MAX_APP_CODE_RETRY = 10;
        /** 默认分页大小 */
        public static final int DEFAULT_PAGE_SIZE = 10;
        /** 最大分页大小 */
        public static final int MAX_PAGE_SIZE = 100;
        /** 防重放攻击时间窗口（秒） */
        public static final int REPLAY_WINDOW = 300;
        /** API密钥长度 */
        public static final int API_KEY_LENGTH = 32;
        /** API密钥密文长度 */
        public static final int API_SECRET_LENGTH = 48;
        /** 应用标识码长度 */
        public static final int APP_CODE_LENGTH = 16;
    }

    /**
     * 错误消息常量
     */
    public static final class ErrorMessage {
        public static final String INVALID_APP_ID = "应用ID无效";
        public static final String INVALID_APP_CODE = "应用标识码无效";
        public static final String INVALID_API_KEY = "API密钥无效";
        public static final String APP_NOT_FOUND = "应用不存在";
        public static final String APP_DISABLED = "应用已被禁用";
        public static final String KEY_NOT_FOUND = "密钥不存在";
        public static final String KEY_DISABLED = "密钥已被禁用";
        public static final String KEY_EXPIRED = "密钥已过期";
        public static final String INVALID_SIGNATURE = "签名无效";
        public static final String REQUEST_EXPIRED = "请求已过期";
        public static final String NONCE_USED = "Nonce已被使用";
        public static final String IP_NOT_ALLOWED = "IP地址不在白名单中";
        public static final String RATE_LIMIT_EXCEEDED = "请求频率超限";
        public static final String PERMISSION_DENIED = "权限不足";
        public static final String INVALID_CALLBACK_URL = "回调URL格式不正确";
        public static final String INVALID_IP_FORMAT = "IP地址格式不正确";
        public static final String USER_NOT_LOGIN = "用户未登录";
        public static final String SYSTEM_ERROR = "系统错误";
        public static final String PARAM_ERROR = "参数错误";
    }

    /**
     * 成功消息常量
     */
    public static final class SuccessMessage {
        public static final String APP_REGISTER_SUCCESS = "应用注册成功";
        public static final String APP_APPROVE_SUCCESS = "应用审核成功";
        public static final String APP_UPDATE_SUCCESS = "应用更新成功";
        public static final String APP_DELETE_SUCCESS = "应用删除成功";
        public static final String KEY_GENERATE_SUCCESS = "密钥生成成功";
        public static final String KEY_ROTATE_SUCCESS = "密钥轮换成功";
        public static final String KEY_DELETE_SUCCESS = "密钥删除成功";
        public static final String PERMISSION_GRANT_SUCCESS = "权限授予成功";
        public static final String PERMISSION_REVOKE_SUCCESS = "权限撤销成功";
    }
}