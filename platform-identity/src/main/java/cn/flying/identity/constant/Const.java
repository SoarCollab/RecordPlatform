package cn.flying.identity.constant;

/**
 * 系统常量定义
 * 从 platform-backend 迁移而来，包含用户权限相关的常量
 * 
 * @author 王贝强
 */
public final class Const {
    
    // ==================== JWT令牌相关 ====================
    /**
     * JWT黑名单前缀
     */
    public final static String JWT_BLACK_LIST = "jwt:blacklist:";
    
    /**
     * JWT频率限制前缀
     */
    public final static String JWT_FREQUENCY = "jwt:frequency:";
    
    // ==================== 请求频率限制 ====================
    /**
     * 流量限制计数器前缀
     */
    public final static String FLOW_LIMIT_COUNTER = "flow:counter:";
    
    /**
     * 流量限制阻塞前缀
     */
    public final static String FLOW_LIMIT_BLOCK = "flow:block:";
    
    // ==================== 邮件验证码 ====================
    /**
     * 邮件验证码限制前缀
     */
    public final static String VERIFY_EMAIL_LIMIT = "verify:email:limit:";
    
    /**
     * 邮件验证码数据前缀
     */
    public final static String VERIFY_EMAIL_DATA = "verify:email:data:";
    
    // ==================== 过滤器优先级 ====================
    /**
     * 流量限制过滤器优先级
     */
    public final static int ORDER_FLOW_LIMIT = -101;
    
    /**
     * CORS过滤器优先级
     */
    public final static int ORDER_CORS = -102;
    
    /**
     * 安全过滤器优先级
     */
    public final static int SECURITY_ORDER = -99;
    
    /**
     * 日志过滤器优先级
     */
    public final static int LOG_ORDER = -100;
    
    /**
     * ID安全过滤器优先级
     */
    public final static int ORDER_ID_SECURITY = 100;
    
    // ==================== 请求自定义属性 ====================
    /**
     * 用户ID属性名
     */
    public final static String ATTR_USER_ID = "userId";
    
    /**
     * 用户角色属性名
     */
    public final static String ATTR_USER_ROLE = "userRole";
    
    /**
     * 请求ID属性名
     */
    public final static String ATTR_REQ_ID = "reqId";
    
    // ==================== 消息队列 ====================
    /**
     * 邮件消息队列
     */
    public final static String MQ_MAIL = "mail";
    
    // ==================== 用户角色 ====================
    /**
     * 默认用户角色
     */
    public final static String ROLE_DEFAULT = "user";
    
    /**
     * 管理员角色
     */
    public final static String ROLE_ADMINISTER = "admin";
    
    /**
     * 监控员角色
     */
    public final static String ROLE_MONITOR = "monitor";
    
    // ==================== 文件相关 ====================
    /**
     * 图片计数器前缀
     */
    public final static String IMAGE_COUNTER = "image:";
    
    // ==================== 用户相关实体类(用于AOP判断是否混淆ID) ====================
    /**
     * 用户实体标识
     */
    public final static String USER_ENTITY = "user";
    
    /**
     * 账户实体标识
     */
    public final static String ACCOUNT_ENTITY = "account";
    
    // ==================== SA-Token相关 ====================
    /**
     * SA-Token 登录类型
     */
    public final static String LOGIN_TYPE_DEFAULT = "login";
    
    /**
     * OAuth2.0 登录类型
     */
    public final static String LOGIN_TYPE_OAUTH = "oauth";
    
    /**
     * SSO 登录类型
     */
    public final static String LOGIN_TYPE_SSO = "sso";
    
    // ==================== Redis键前缀 ====================
    /**
     * 用户会话前缀
     */
    public final static String USER_SESSION_PREFIX = "user:session:";
    
    /**
     * OAuth授权码前缀
     */
    public final static String OAUTH_CODE_PREFIX = "oauth:code:";
    
    /**
     * OAuth访问令牌前缀
     */
    public final static String OAUTH_ACCESS_TOKEN_PREFIX = "oauth:access_token:";
    
    /**
     * OAuth刷新令牌前缀
     */
    public final static String OAUTH_REFRESH_TOKEN_PREFIX = "oauth:refresh_token:";
    
    /**
     * 私有构造函数，防止实例化
     */
    private Const() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
