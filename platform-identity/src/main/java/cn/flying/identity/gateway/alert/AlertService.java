package cn.flying.identity.gateway.alert;

import cn.flying.identity.service.SmsService;
import cn.flying.platformapi.constant.Result;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 告警服务
 * 提供多渠道告警通知和告警管理功能
 *
 * 核心功能：
 * 1. 多渠道告警（邮件、短信、钉钉、企业微信）
 * 2. 告警规则配置
 * 3. 告警级别管理
 * 4. 告警抑制和聚合
 * 5. 告警统计和历史记录
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Service
public class AlertService {

    /**
     * 邮件告警开关
     */
    @Value("${api.gateway.alert.email.enabled:false}")
    private boolean emailEnabled;

    /**
     * 邮件发件人地址
     */
    @Value("${api.gateway.alert.email.from:alert@system.com}")
    private String emailFrom;

    /**
     * 短信告警开关
     */
    @Value("${api.gateway.alert.sms.enabled:false}")
    private boolean smsEnabled;

    /**
     * 钉钉告警开关
     */
    @Value("${api.gateway.alert.dingtalk.enabled:false}")
    private boolean dingtalkEnabled;

    /**
     * 钉钉机器人webhook地址
     */
    @Value("${api.gateway.alert.dingtalk.webhook:}")
    private String dingtalkWebhook;

    /**
     * 钉钉机器人密钥（可选，用于签名）
     */
    @Value("${api.gateway.alert.dingtalk.secret:}")
    private String dingtalkSecret;

    /**
     * 企业微信告警开关
     */
    @Value("${api.gateway.alert.wechat.enabled:false}")
    private boolean wechatEnabled;

    /**
     * 企业微信机器人webhook地址
     */
    @Value("${api.gateway.alert.wechat.webhook:}")
    private String wechatWebhook;

    /**
     * 告警抑制时间窗口（秒）
     */
    @Value("${api.gateway.alert.suppress-window:300}")
    private int suppressWindow;

    /**
     * 告警聚合阈值
     */
    @Value("${api.gateway.alert.aggregate-threshold:10}")
    private int aggregateThreshold;

    /**
     * CRITICAL级别邮件接收人列表
     */
    @Value("${api.gateway.alert.email.recipients.critical:}")
    private String emailRecipientsCritical;

    /**
     * ERROR级别邮件接收人列表
     */
    @Value("${api.gateway.alert.email.recipients.error:}")
    private String emailRecipientsError;

    /**
     * WARNING级别邮件接收人列表
     */
    @Value("${api.gateway.alert.email.recipients.warning:}")
    private String emailRecipientsWarning;

    /**
     * INFO级别邮件接收人列表
     */
    @Value("${api.gateway.alert.email.recipients.info:}")
    private String emailRecipientsInfo;

    /**
     * CRITICAL级别短信接收人列表
     */
    @Value("${api.gateway.alert.sms.recipients.critical:}")
    private String smsRecipientsCritical;

    /**
     * ERROR级别短信接收人列表
     */
    @Value("${api.gateway.alert.sms.recipients.error:}")
    private String smsRecipientsError;

    /**
     * WARNING级别短信接收人列表
     */
    @Value("${api.gateway.alert.sms.recipients.warning:}")
    private String smsRecipientsWarning;

    /**
     * 告警规则映射
     */
    private final Map<String, AlertRule> alertRules = new ConcurrentHashMap<>();

    /**
     * 告警记录
     */
    private final Map<String, AlertRecord> alertRecords = new ConcurrentHashMap<>();

    /**
     * 告警抑制缓存
     */
    private final Map<String, Long> suppressCache = new ConcurrentHashMap<>();

    /**
     * 告警聚合缓存
     */
    private final Map<String, List<Alert>> aggregateCache = new ConcurrentHashMap<>();

    /**
     * 告警统计
     */
    private final AlertStatistics statistics = new AlertStatistics();

    /**
     * 告警执行器
     */
    private ScheduledExecutorService alertExecutor;

    /**
     * HTTP客户端（用于发送webhook）
     */
    private RestTemplate restTemplate;

    /**
     * 短信服务（使用SMS4J框架）
     */
    @Resource
    private SmsService smsService;

    /**
     * 邮件发送器（可选，未配置时为null）
     */
    @Autowired(required = false)
    private JavaMailSender mailSender;

    /**
     * 初始化告警服务
     */
    @PostConstruct
    public void init() {
        log.info("初始化告警服务...");

        // 创建告警执行器
        alertExecutor = Executors.newScheduledThreadPool(2,
                r -> new Thread(r, "AlertExecutor"));

        // 初始化HTTP客户端
        restTemplate = new RestTemplate();

        // 初始化默认告警规则
        initDefaultAlertRules();

        // 启动告警聚合任务
        startAggregateTask();

        log.info("告警服务初始化完成");
    }

    /**
     * 初始化默认告警规则
     */
    private void initDefaultAlertRules() {
        // CPU使用率告警
        registerAlertRule(new AlertRule("cpu_high", "CPU使用率过高",
                AlertLevel.WARNING, 80, 90, "cpu_usage"));

        // 内存使用率告警
        registerAlertRule(new AlertRule("memory_high", "内存使用率过高",
                AlertLevel.WARNING, 80, 90, "memory_usage"));

        // 错误率告警
        registerAlertRule(new AlertRule("error_rate_high", "错误率过高",
                AlertLevel.ERROR, 5, 10, "error_rate"));

        // 响应时间告警
        registerAlertRule(new AlertRule("response_time_high", "响应时间过长",
                AlertLevel.WARNING, 1000, 3000, "response_time"));

        // 熔断器打开告警
        registerAlertRule(new AlertRule("circuit_breaker_open", "熔断器打开",
                AlertLevel.ERROR, 1, 1, "circuit_breaker"));

        // QPS告警
        registerAlertRule(new AlertRule("qps_high", "QPS过高",
                AlertLevel.WARNING, 1000, 5000, "qps"));
    }

    /**
     * 简化的发送告警接口（供外部调用）
     *
     * @param type 告警类型
     * @param message 告警消息
     * @param level 告警级别（HIGH/MEDIUM/LOW）
     */
    public void sendAlert(String type, String message, String level) {
        Alert alert = new Alert();
        alert.setType(type);
        alert.setTitle(type);
        alert.setContent(message);
        alert.setTime(LocalDateTime.now());
        alert.setSource("API_GATEWAY");

        // 转换级别
        AlertLevel alertLevel = switch (level.toUpperCase()) {
            case "CRITICAL", "HIGH" -> AlertLevel.CRITICAL;
            case "ERROR", "MEDIUM" -> AlertLevel.ERROR;
            case "WARNING", "LOW" -> AlertLevel.WARNING;
            default -> AlertLevel.INFO;
        };
        alert.setLevel(alertLevel);

        // 调用内部发送方法
        sendAlert(alert);
    }

    /**
     * 发送告警
     *
     * @param alert 告警信息
     */
    @Async
    public void sendAlert(Alert alert) {
        try {
            // 检查是否需要抑制
            if (shouldSuppress(alert)) {
                log.debug("告警被抑制: {}", alert);
                statistics.recordSuppressed();
                return;
            }

            // 检查是否需要聚合
            if (shouldAggregate(alert)) {
                aggregate(alert);
                return;
            }

            // 执行告警发送
            doSendAlert(alert);

            // 记录告警
            recordAlert(alert);

            // 更新统计
            statistics.recordSent(alert.getLevel());

        } catch (Exception e) {
            log.error("发送告警失败", e);
            statistics.recordFailed();
        }
    }

    /**
     * 实际发送告警
     *
     * @param alert 告警信息
     */
    private void doSendAlert(Alert alert) {
        log.info("发送告警: level={}, title={}, content={}",
                alert.getLevel(), alert.getTitle(), alert.getContent());

        // 根据告警级别选择通道
        switch (alert.getLevel()) {
            case CRITICAL:
                // 严重告警：所有通道
                sendEmail(alert);
                sendSms(alert);
                sendDingTalk(alert);
                sendWeChat(alert);
                break;
            case ERROR:
                // 错误告警：邮件和即时通讯
                sendEmail(alert);
                sendDingTalk(alert);
                sendWeChat(alert);
                break;
            case WARNING:
                // 警告告警：即时通讯
                sendDingTalk(alert);
                sendWeChat(alert);
                break;
            case INFO:
                // 信息告警：仅记录
                log.info("信息告警: {}", alert);
                break;
        }
    }

    /**
     * 发送邮件告警
     *
     * 注意：这是一个基础实现框架。生产环境应该：
     * 1. 集成邮件服务提供商（如SendGrid、阿里云邮件推送、腾讯云邮件）
     * 2. 配置SMTP服务器信息（host、port、username、password）
     * 3. 使用Spring Boot的JavaMailSender进行邮件发送
     * 4. 实现邮件模板管理
     * 5. 添加邮件发送队列和重试机制
     */
    private void sendEmail(Alert alert) {
        if (!emailEnabled) {
            return;
        }

        try {
            // 基础实现框架
            String emailSubject = String.format("[%s告警] %s", alert.getLevel(), alert.getTitle());
            String emailBody = buildEmailBody(alert);

            // 获取收件人列表（从配置或数据库读取）
            List<String> recipients = getEmailRecipients(alert.getLevel());

            if (recipients.isEmpty()) {
                log.debug("告警级别{}没有配置邮件接收人，跳过邮件发送", alert.getLevel());
                return;
            }

            log.info("准备发送邮件告警: 主题={}, 收件人={}, 级别={}",
                    emailSubject, recipients, alert.getLevel());

            // 检查邮件发送器是否配置
            if (mailSender == null) {
                log.warn("邮件发送器未配置，无法发送邮件告警。请配置spring.mail相关属性");
                return;
            }

            // 实际发送邮件
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(recipients.toArray(new String[0]));
            message.setSubject(emailSubject);
            message.setText(emailBody);
            message.setFrom(emailFrom); // 发件人地址从配置读取

            mailSender.send(message);
            log.info("邮件告警已发送: 收件人={}, 主题={}", recipients, alert.getTitle());
        } catch (Exception e) {
            log.error("发送邮件告警失败: {}", alert.getTitle(), e);
        }
    }

    /**
     * 构建邮件正文
     */
    private String buildEmailBody(Alert alert) {
        StringBuilder body = new StringBuilder();
        body.append("告警详情\n");
        body.append("================\n");
        body.append("告警级别: ").append(alert.getLevel()).append("\n");
        body.append("告警标题: ").append(alert.getTitle()).append("\n");
        body.append("告警时间: ").append(alert.getTime()).append("\n");
        body.append("告警来源: ").append(alert.getSource()).append("\n");
        body.append("告警内容: ").append(alert.getContent()).append("\n");
        if (alert.getTags() != null && !alert.getTags().isEmpty()) {
            body.append("附加信息: ").append(alert.getTags()).append("\n");
        }
        return body.toString();
    }

    /**
     * 获取邮件收件人列表
     * 从配置文件读取各级别的邮件接收人地址
     */
    private List<String> getEmailRecipients(AlertLevel level) {
        List<String> recipients = new ArrayList<>();
        String recipientsConfig = null;

        // 根据告警级别获取对应的配置
        switch (level) {
            case CRITICAL:
                recipientsConfig = emailRecipientsCritical;
                break;
            case ERROR:
                recipientsConfig = emailRecipientsError;
                break;
            case WARNING:
                recipientsConfig = emailRecipientsWarning;
                break;
            case INFO:
                recipientsConfig = emailRecipientsInfo;
                break;
            default:
                return recipients;
        }

        // 解析配置的邮箱列表（逗号分隔）
        if (recipientsConfig != null && !recipientsConfig.trim().isEmpty()) {
            String[] emails = recipientsConfig.split(",");
            for (String email : emails) {
                String trimmedEmail = email.trim();
                if (!trimmedEmail.isEmpty()) {
                    recipients.add(trimmedEmail);
                }
            }
        }

        if (recipients.isEmpty() && level == AlertLevel.CRITICAL) {
            log.warn("CRITICAL级别告警未配置邮件接收人，请在配置文件中设置 api.gateway.alert.email.recipients.critical");
        }

        return recipients;
    }

    /**
     * 发送短信告警
     * 使用集成的SMS4J框架通过SmsService发送短信
     */
    private void sendSms(Alert alert) {
        if (!smsEnabled) {
            return;
        }

        try {
            // 构建短信内容（注意短信有长度限制，通常为70个字符）
            String smsContent = buildSmsContent(alert);

            // 获取接收人手机号列表
            List<String> phoneNumbers = getSmsRecipients(alert.getLevel());

            if (phoneNumbers.isEmpty()) {
                log.debug("告警级别{}没有配置短信接收人，跳过短信发送", alert.getLevel());
                return;
            }

            log.info("准备发送短信告警: 内容长度={}, 接收人数={}, 级别={}",
                    smsContent.length(), phoneNumbers.size(), alert.getLevel());

            // 使用SmsService发送短信（已集成SMS4J框架）
            int successCount = 0;
            int failCount = 0;

            for (String phone : phoneNumbers) {
                // 使用SmsService发送短信
                Result<Boolean> result = smsService.sendMessage(phone, smsContent);

                if (result.isSuccess() && Boolean.TRUE.equals(result.getData())) {
                    successCount++;
                    log.info("短信告警发送成功: 手机号={}, 内容长度={}",
                            maskPhoneNumber(phone), smsContent.length());
                } else {
                    failCount++;
                    log.error("短信告警发送失败: 手机号={}, 错误信息={}",
                            maskPhoneNumber(phone), result.getMessage());
                }
            }

            // 记录发送统计
            if (successCount > 0) {
                log.info("短信告警发送完成: 成功={}, 失败={}, 告警标题={}",
                        successCount, failCount, alert.getTitle());
            } else {
                log.error("短信告警全部发送失败: 失败数={}, 告警标题={}",
                        failCount, alert.getTitle());
            }

        } catch (Exception e) {
            log.error("发送短信告警异常: 告警标题={}", alert.getTitle(), e);
        }
    }

    /**
     * 构建短信内容
     */
    private String buildSmsContent(Alert alert) {
        // 短信内容需要简洁，控制在70个字符以内
        String content = String.format("【系统告警】%s级别:%s,内容:%s,时间:%s",
                alert.getLevel(),
                alert.getTitle(),
                truncateString(alert.getContent(), 30),
                alert.getTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));

        // 确保不超过短信长度限制
        if (content.length() > 70) {
            content = content.substring(0, 67) + "...";
        }
        return content;
    }

    /**
     * 获取短信接收人列表
     * 从配置文件读取各级别的短信接收人手机号
     */
    private List<String> getSmsRecipients(AlertLevel level) {
        List<String> phoneNumbers = new ArrayList<>();
        String recipientsConfig = null;

        // 根据告警级别获取对应的配置
        switch (level) {
            case CRITICAL:
                recipientsConfig = smsRecipientsCritical;
                break;
            case ERROR:
                recipientsConfig = smsRecipientsError;
                break;
            case WARNING:
                recipientsConfig = smsRecipientsWarning;
                break;
            case INFO:
                // INFO级别默认不发送短信
                return phoneNumbers;
            default:
                return phoneNumbers;
        }

        // 解析配置的手机号列表（逗号分隔）
        if (recipientsConfig != null && !recipientsConfig.trim().isEmpty()) {
            String[] phones = recipientsConfig.split(",");
            for (String phone : phones) {
                String trimmedPhone = phone.trim();
                if (!trimmedPhone.isEmpty()) {
                    phoneNumbers.add(trimmedPhone);
                }
            }
        }

        if (phoneNumbers.isEmpty() && level == AlertLevel.CRITICAL) {
            log.warn("CRITICAL级别告警未配置短信接收人，请在配置文件中设置 api.gateway.alert.sms.recipients.critical");
        }

        return phoneNumbers;
    }

    /**
     * 脱敏手机号
     */
    private String maskPhoneNumber(String phone) {
        if (phone == null || phone.length() < 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /**
     * 截断字符串
     */
    private String truncateString(String str, int maxLength) {
        if (str == null) {
            return "";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength);
    }

    /**
     * 发送钉钉告警
     * 实现钉钉机器人webhook调用
     */
    private void sendDingTalk(Alert alert) {
        if (!dingtalkEnabled || dingtalkWebhook == null || dingtalkWebhook.isEmpty()) {
            return;
        }

        try {
            // 构建钉钉消息体
            Map<String, Object> message = new HashMap<>();
            message.put("msgtype", "text");

            Map<String, String> text = new HashMap<>();
            String content = String.format("【%s告警】%s\n时间：%s\n详情：%s",
                    alert.getLevel(),
                    alert.getTitle(),
                    alert.getTime(),
                    alert.getContent());
            text.put("content", content);
            message.put("text", text);

            // 可选：at特定用户
            Map<String, Object> at = new HashMap<>();
            at.put("isAtAll", alert.getLevel() == AlertLevel.CRITICAL);
            message.put("at", at);

            // 发送HTTP请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(dingtalkWebhook, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("钉钉告警发送成功: {}", alert.getTitle());
            } else {
                log.error("钉钉告警发送失败: status={}, body={}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("发送钉钉告警失败: {}", alert.getTitle(), e);
        }
    }

    /**
     * 发送企业微信告警
     * 实现企业微信机器人webhook调用
     */
    private void sendWeChat(Alert alert) {
        if (!wechatEnabled || wechatWebhook == null || wechatWebhook.isEmpty()) {
            return;
        }

        try {
            // 构建企业微信消息体
            Map<String, Object> message = new HashMap<>();
            message.put("msgtype", "text");

            Map<String, Object> text = new HashMap<>();
            String content = String.format("【%s告警】%s\n时间：%s\n详情：%s",
                    alert.getLevel(),
                    alert.getTitle(),
                    alert.getTime(),
                    alert.getContent());
            text.put("content", content);
            // 企业微信支持markdown，可根据级别添加颜色
            if (alert.getLevel() == AlertLevel.CRITICAL) {
                text.put("mentioned_list", List.of("@all"));
            }
            message.put("text", text);

            // 发送HTTP请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(wechatWebhook, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("企业微信告警发送成功: {}", alert.getTitle());
            } else {
                log.error("企业微信告警发送失败: status={}, body={}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("发送企业微信告警失败: {}", alert.getTitle(), e);
        }
    }

    /**
     * 检查是否需要抑制告警
     */
    private boolean shouldSuppress(Alert alert) {
        String key = alert.getType() + ":" + alert.getSource();
        Long lastTime = suppressCache.get(key);

        if (lastTime != null) {
            long elapsed = System.currentTimeMillis() - lastTime;
            if (elapsed < suppressWindow * 1000L) {
                log.debug("告警在抑制窗口内: key={}, elapsed={}ms", key, elapsed);
                return true;
            }
        }

        // 更新抑制缓存
        suppressCache.put(key, System.currentTimeMillis());
        return false;
    }

    /**
     * 检查是否需要聚合告警
     */
    private boolean shouldAggregate(Alert alert) {
        // INFO级别不聚合
        if (alert.getLevel() == AlertLevel.INFO) {
            return false;
        }

        String key = alert.getType();
        List<Alert> cached = aggregateCache.computeIfAbsent(key, k -> new ArrayList<>());

        return cached.size() < aggregateThreshold;
    }

    /**
     * 聚合告警
     */
    private void aggregate(Alert alert) {
        String key = alert.getType();
        List<Alert> cached = aggregateCache.computeIfAbsent(key, k -> new ArrayList<>());

        synchronized (cached) {
            cached.add(alert);
            log.debug("聚合告警: type={}, count={}", key, cached.size());

            // 达到聚合阈值，发送聚合告警
            if (cached.size() >= aggregateThreshold) {
                sendAggregatedAlert(key, new ArrayList<>(cached));
                cached.clear();
            }
        }
    }

    /**
     * 发送聚合告警
     */
    private void sendAggregatedAlert(String type, List<Alert> alerts) {
        Alert aggregated = new Alert();
        aggregated.setType(type + "_aggregated");
        aggregated.setLevel(getMaxLevel(alerts));
        aggregated.setTitle(String.format("【聚合告警】%s（%d条）", type, alerts.size()));

        StringBuilder content = new StringBuilder();
        content.append("聚合了").append(alerts.size()).append("条告警:\n");
        for (int i = 0; i < Math.min(5, alerts.size()); i++) {
            Alert alert = alerts.get(i);
            content.append(String.format("%d. %s - %s\n",
                    i + 1, alert.getTitle(), alert.getTime()));
        }
        if (alerts.size() > 5) {
            content.append("...\n");
        }
        aggregated.setContent(content.toString());

        doSendAlert(aggregated);
    }

    /**
     * 获取最高告警级别
     */
    private AlertLevel getMaxLevel(List<Alert> alerts) {
        return alerts.stream()
                .map(Alert::getLevel)
                .max(Comparator.comparing(AlertLevel::getPriority))
                .orElse(AlertLevel.INFO);
    }

    /**
     * 记录告警
     */
    private void recordAlert(Alert alert) {
        String recordId = UUID.randomUUID().toString();
        AlertRecord record = new AlertRecord();
        record.setId(recordId);
        record.setAlert(alert);
        record.setSendTime(LocalDateTime.now());
        record.setStatus("sent");

        alertRecords.put(recordId, record);

        // 保留最近1000条记录
        if (alertRecords.size() > 1000) {
            alertRecords.keySet().stream()
                    .limit(100)
                    .forEach(alertRecords::remove);
        }
    }

    /**
     * 注册告警规则
     */
    public void registerAlertRule(AlertRule rule) {
        alertRules.put(rule.getRuleId(), rule);
        log.info("注册告警规则: {}", rule);
    }

    /**
     * 检查指标并触发告警
     */
    public void checkMetricsAndAlert(String metric, double value) {
        alertRules.values().stream()
                .filter(rule -> rule.getMetric().equals(metric))
                .filter(rule -> rule.shouldAlert(value))
                .forEach(rule -> {
                    Alert alert = new Alert();
                    alert.setType(rule.getRuleId());
                    alert.setLevel(rule.getLevel());
                    alert.setTitle(rule.getName());
                    alert.setContent(String.format("%s: 当前值 %.2f, 阈值 %.2f",
                            rule.getName(), value, rule.getThreshold()));
                    alert.setSource(metric);
                    alert.setTime(LocalDateTime.now());

                    sendAlert(alert);
                });
    }

    /**
     * 启动告警聚合任务
     */
    private void startAggregateTask() {
        // 每分钟检查一次聚合缓存
        alertExecutor.scheduleWithFixedDelay(() -> {
            try {
                aggregateCache.forEach((type, alerts) -> {
                    if (!alerts.isEmpty()) {
                        synchronized (alerts) {
                            if (!alerts.isEmpty()) {
                                log.info("定时发送聚合告警: type={}, count={}", type, alerts.size());
                                sendAggregatedAlert(type, new ArrayList<>(alerts));
                                alerts.clear();
                            }
                        }
                    }
                });
            } catch (Exception e) {
                log.error("处理聚合告警失败", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 获取告警统计信息
     */
    public Map<String, Object> getStatistics() {
        return statistics.toMap();
    }

    /**
     * 告警信息
     */
    @Data
    public static class Alert {
        private String type;
        private AlertLevel level;
        private String title;
        private String content;
        private String source;
        private LocalDateTime time;
        private Map<String, Object> tags;
    }

    /**
     * 告警级别
     */
    public enum AlertLevel {
        INFO(0),
        WARNING(1),
        ERROR(2),
        CRITICAL(3);

        private final int priority;

        AlertLevel(int priority) {
            this.priority = priority;
        }

        public int getPriority() {
            return priority;
        }
    }

    /**
     * 告警规则
     */
    @Data
    public static class AlertRule {
        private String ruleId;
        private String name;
        private AlertLevel level;
        private double threshold;
        private double criticalThreshold;
        private String metric;

        public AlertRule(String ruleId, String name, AlertLevel level,
                        double threshold, double criticalThreshold, String metric) {
            this.ruleId = ruleId;
            this.name = name;
            this.level = level;
            this.threshold = threshold;
            this.criticalThreshold = criticalThreshold;
            this.metric = metric;
        }

        public boolean shouldAlert(double value) {
            return value >= threshold;
        }

        public AlertLevel getAlertLevel(double value) {
            if (value >= criticalThreshold) {
                return AlertLevel.CRITICAL;
            } else if (value >= threshold) {
                return level;
            }
            return null;
        }
    }

    /**
     * 告警记录
     */
    @Data
    private static class AlertRecord {
        private String id;
        private Alert alert;
        private LocalDateTime sendTime;
        private String status;
    }

    /**
     * 告警统计
     */
    private static class AlertStatistics {
        private final AtomicInteger totalSent = new AtomicInteger(0);
        private final AtomicInteger totalSuppressed = new AtomicInteger(0);
        private final AtomicInteger totalFailed = new AtomicInteger(0);
        private final Map<AlertLevel, AtomicInteger> levelCounts = new ConcurrentHashMap<>();

        public AlertStatistics() {
            for (AlertLevel level : AlertLevel.values()) {
                levelCounts.put(level, new AtomicInteger(0));
            }
        }

        public void recordSent(AlertLevel level) {
            totalSent.incrementAndGet();
            levelCounts.get(level).incrementAndGet();
        }

        public void recordSuppressed() {
            totalSuppressed.incrementAndGet();
        }

        public void recordFailed() {
            totalFailed.incrementAndGet();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("totalSent", totalSent.get());
            map.put("totalSuppressed", totalSuppressed.get());
            map.put("totalFailed", totalFailed.get());

            Map<String, Integer> levelStats = new HashMap<>();
            levelCounts.forEach((level, count) ->
                    levelStats.put(level.name(), count.get()));
            map.put("byLevel", levelStats);

            return map;
        }
    }
}