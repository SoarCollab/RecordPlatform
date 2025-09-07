package cn.flying.identity.event;

import cn.flying.identity.dto.TokenMonitor;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token监控事件
 * 用于异步处理Token监控记录
 *
 * @author flying
 * @date 2024
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenMonitorEvent {

    /**
     * Token监控对象
     */
    private TokenMonitor tokenMonitor;

    /**
     * 事件类型
     */
    private String eventType;

    /**
     * 事件时间戳
     */
    private Long timestamp;

    /**
     * 构造函数
     *
     * @param tokenMonitor Token监控记录
     */
    public TokenMonitorEvent(TokenMonitor tokenMonitor) {
        this.tokenMonitor = tokenMonitor;
        this.eventType = "TOKEN_MONITOR";
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 构造函数
     *
     * @param tokenMonitor Token监控记录
     * @param eventType    事件类型
     */
    public TokenMonitorEvent(TokenMonitor tokenMonitor, String eventType) {
        this.tokenMonitor = tokenMonitor;
        this.eventType = eventType;
        this.timestamp = System.currentTimeMillis();
    }
}