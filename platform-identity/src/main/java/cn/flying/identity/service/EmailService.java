package cn.flying.identity.service;

/**
 * 邮件服务接口
 * 定义邮件发送相关功能
 */
public interface EmailService {

    /**
     * 发送验证码邮件
     *
     * @param email 收件人邮箱
     * @param code  验证码
     * @param type  邮件类型（register/reset）
     * @return 发送结果，true-成功，false-失败
     */
    boolean sendVerifyCode(String email, String code, String type);

    /**
     * 发送普通邮件
     *
     * @param to      收件人邮箱
     * @param subject 邮件主题
     * @param content 邮件内容
     * @return 发送结果，true-成功，false-失败
     */
    boolean sendSimpleMail(String to, String subject, String content);

    /**
     * 发送HTML邮件
     *
     * @param to          收件人邮箱
     * @param subject     邮件主题
     * @param htmlContent HTML邮件内容
     * @return 发送结果，true-成功，false-失败
     */
    boolean sendHtmlMail(String to, String subject, String htmlContent);
}
