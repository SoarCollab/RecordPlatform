package cn.flying.service.generator;

import cn.flying.dao.mapper.TicketMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 分布式工单编号生成器
 * 使用 Redis 保证多实例部署时的唯一性
 *
 * 设计要点：
 * 1. 使用 Redis INCR 保证原子性和分布式唯一性
 * 2. 每日自动重置序列号
 * 3. Redis 不可用时回退到数据库查询
 */
@Slf4j
@Component
public class TicketNoGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String REDIS_KEY_PREFIX = "ticket:seq:";
    private static final String TICKET_NO_PREFIX = "TK";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TicketMapper ticketMapper;

    /**
     * 生成工单编号（分布式安全）
     *
     * @return 工单编号，格式：TK + 日期(yyyyMMdd) + 4位序列号
     */
    public String generateTicketNo() {
        String today = LocalDate.now().format(DATE_FORMATTER);
        String redisKey = REDIS_KEY_PREFIX + today;

        try {
            // 使用 Redis INCR 原子递增
            Long sequence = stringRedisTemplate.opsForValue().increment(redisKey);
            if (sequence == null) {
                sequence = 1L;
            }

            // 首次生成时设置过期时间（48小时，跨日缓冲）
            if (sequence == 1L) {
                stringRedisTemplate.expire(redisKey, 48, TimeUnit.HOURS);
            }

            return formatTicketNo(today, sequence.intValue());

        } catch (Exception e) {
            log.warn("Redis 不可用，回退到数据库查询: {}", e.getMessage());
            return generateFromDatabase(today);
        }
    }

    /**
     * 从数据库获取最大序列号（Redis 不可用时的回退方案）
     */
    private synchronized String generateFromDatabase(String datePrefix) {
        Integer maxSeq = ticketMapper.getMaxDailySequence(datePrefix);
        int nextSeq = (maxSeq == null ? 0 : maxSeq) + 1;
        return formatTicketNo(datePrefix, nextSeq);
    }

    private String formatTicketNo(String dateStr, int sequence) {
        return TICKET_NO_PREFIX + dateStr + String.format("%04d", sequence);
    }
}
