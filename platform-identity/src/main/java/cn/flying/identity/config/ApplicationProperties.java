package cn.flying.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用程序配置属性
 * 集中管理应用的各种配置信息，避免硬编码
 * 
 * @author 王贝强
 */
@Data
@Component
@ConfigurationProperties(prefix = "platform.identity")
public class ApplicationProperties {
    
    /**
     * 应用信息配置
     */
    private AppInfo appInfo = new AppInfo();
    
    /**
     * 启动检查配置
     */
    private StartupCheck startupCheck = new StartupCheck();
    
    /**
     * 密码配置
     */
    private Password password = new Password();
    
    /**
     * 验证码配置
     */
    private VerifyCode verifyCode = new VerifyCode();
    
    /**
     * 应用信息配置
     */
    @Data
    public static class AppInfo {
        /**
         * 应用名称
         */
        private String name = "存证平台认证服务";
        
        /**
         * 应用版本
         */
        private String version = "1.0.0";
        
        /**
         * 认证框架
         */
        private String authFramework = "SA-Token";
        
        /**
         * API文档地址
         */
        private String apiDocUrl = "http://localhost:8081/identity/doc.html";
    }
    
    /**
     * 启动检查配置
     */
    @Data
    public static class StartupCheck {
        /**
         * 是否启用启动检查
         */
        private boolean enabled = true;
        
        /**
         * 数据库连接超时时间（秒）
         */
        private int dbConnectionTimeout = 5;
        
        /**
         * Redis测试键名
         */
        private String redisTestKey = "startup:check";
        
        /**
         * Redis测试值
         */
        private String redisTestValue = "ok";
    }
    
    /**
     * 密码配置
     */
    @Data
    public static class Password {
        /**
         * 密码加密强度
         */
        private int strength = 12;
        
        /**
         * 最小密码长度
         */
        private int minLength = 6;
        
        /**
         * 最大密码长度
         */
        private int maxLength = 50;
    }
    
    /**
     * 验证码配置
     */
    @Data
    public static class VerifyCode {
        /**
         * 邮件验证码发送冷却时间（秒）
         */
        private int emailLimit = 60;
        
        /**
         * 验证码有效期（分钟）
         */
        private int expireMinutes = 10;
        
        /**
         * 验证码长度
         */
        private int length = 6;
    }
}
