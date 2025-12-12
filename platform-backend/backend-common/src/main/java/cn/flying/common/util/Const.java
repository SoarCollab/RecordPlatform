package cn.flying.common.util;

/**
 * 一些常量字符串整合
 */
public final class Const {
    //JWT令牌
    public final static String JWT_BLACK_LIST = "jwt:blacklist:";
    public final static String JWT_FREQUENCY = "jwt:frequency:";
    //请求频率限制
    public final static String FLOW_LIMIT_COUNTER = "flow:counter:";
    public final static String FLOW_LIMIT_BLOCK = "flow:block:";
    //邮件验证码
    public final static String VERIFY_EMAIL_LIMIT = "verify:email:limit:";
    public final static String VERIFY_EMAIL_DATA = "verify:email:data:";
    //过滤器优先级
    public final static int ORDER_SECURITY_HEADER = -105;
    public final static int ORDER_FLOW_LIMIT = -101;
    public final static int ORDER_CORS = -102;
    public final static int SECURITY_ORDER = -99;
    public final static int LOG_ORDER = -100;
    public final static int ORDER_ID_SECURITY = 100;
    //登录安全
    public final static String LOGIN_FAIL_COUNT = "login:fail:";
    public final static int LOGIN_MAX_ATTEMPTS = 5;
    public final static long LOGIN_LOCK_DURATION = 15 * 60;  // 15分钟
    //请求自定义属性
    public final static String ATTR_USER_ID = "userId";
    public final static String ATTR_USER_ROLE = "userRole";
    public final static String ATTR_TENANT_ID = "tenantId";
    public final static String ATTR_REQ_ID = "reqId";
    //分布式追踪
    public final static String TRACE_ID = "traceId";
    //消息队列
    public final static String MQ_MAIL = "mail";
    //文件
    public final static String IMAGE_COUNTER = "image:";

    //用户相关实体类(用于AOP判断是否混淆ID)
    public final static String USER_ENTITY = "user";
    public final static String ACCOUNT_ENTITY = "account";

    //权限缓存
    public final static String PERMISSION_CACHE_PREFIX = "perm:role:";
    public final static long PERMISSION_CACHE_TTL = 30 * 60;  // 30分钟

    //SSE短期令牌
    public final static String SSE_TOKEN_PREFIX = "sse:token:";
    public final static long SSE_TOKEN_TTL = 30;  // 30秒有效期
}
