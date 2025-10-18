package cn.flying.monitor.notification.controller;

import cn.flying.monitor.notification.dto.AlertRuleDTO;
import cn.flying.monitor.notification.entity.AlertRule;
import cn.flying.monitor.notification.service.AlertRuleService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 告警规则管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/alert-rules")
@RequiredArgsConstructor
@Validated
@Tag(name = "告警规则管理", description = "告警规则的创建、更新、删除和查询")
public class AlertRuleController {

    private final AlertRuleService alertRuleService;

    @PostMapping
    @Operation(summary = "创建告警规则", description = "创建新的告警规则")
    public ResponseEntity<AlertRule> createAlertRule(@Valid @RequestBody AlertRuleDTO alertRuleDTO) {
        log.info("Creating alert rule: {}", alertRuleDTO.getName());
        AlertRule alertRule = alertRuleService.createAlertRule(alertRuleDTO);
        return ResponseEntity.ok(alertRule);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新告警规则", description = "根据ID更新告警规则")
    public ResponseEntity<AlertRule> updateAlertRule(
            @Parameter(description = "告警规则ID") @PathVariable Long id,
            @Valid @RequestBody AlertRuleDTO alertRuleDTO) {
        log.info("Updating alert rule ID: {}", id);
        AlertRule alertRule = alertRuleService.updateAlertRule(id, alertRuleDTO);
        return ResponseEntity.ok(alertRule);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除告警规则", description = "根据ID删除告警规则")
    public ResponseEntity<Void> deleteAlertRule(@Parameter(description = "告警规则ID") @PathVariable Long id) {
        log.info("Deleting alert rule ID: {}", id);
        alertRuleService.deleteAlertRule(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询告警规则", description = "根据ID查询告警规则详情")
    public ResponseEntity<AlertRule> getAlertRule(@Parameter(description = "告警规则ID") @PathVariable Long id) {
        AlertRule alertRule = alertRuleService.getAlertRuleById(id);
        if (alertRule == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(alertRule);
    }

    @GetMapping
    @Operation(summary = "分页查询告警规则", description = "分页查询所有告警规则")
    public ResponseEntity<IPage<AlertRule>> getAlertRules(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int current,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") int size) {
        Page<AlertRule> page = new Page<>(current, size);
        IPage<AlertRule> result = alertRuleService.getAlertRules(page);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/enabled")
    @Operation(summary = "查询启用的告警规则", description = "查询所有启用状态的告警规则")
    public ResponseEntity<List<AlertRule>> getEnabledAlertRules() {
        List<AlertRule> alertRules = alertRuleService.getEnabledAlertRules();
        return ResponseEntity.ok(alertRules);
    }

    @GetMapping("/metric/{metricName}")
    @Operation(summary = "根据指标查询告警规则", description = "根据指标名称查询相关的告警规则")
    public ResponseEntity<List<AlertRule>> getAlertRulesByMetric(
            @Parameter(description = "指标名称") @PathVariable String metricName) {
        List<AlertRule> alertRules = alertRuleService.getAlertRulesByMetricName(metricName);
        return ResponseEntity.ok(alertRules);
    }

    @PatchMapping("/{id}/toggle")
    @Operation(summary = "启用/禁用告警规则", description = "切换告警规则的启用状态")
    public ResponseEntity<Void> toggleAlertRule(
            @Parameter(description = "告警规则ID") @PathVariable Long id,
            @Parameter(description = "是否启用") @RequestParam Boolean enabled) {
        log.info("Toggling alert rule ID: {} to enabled: {}", id, enabled);
        alertRuleService.toggleAlertRule(id, enabled);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/validate")
    @Operation(summary = "验证告警规则", description = "验证告警规则配置是否正确")
    public ResponseEntity<Boolean> validateAlertRule(@Valid @RequestBody AlertRuleDTO alertRuleDTO) {
        boolean isValid = alertRuleService.validateAlertRule(alertRuleDTO);
        return ResponseEntity.ok(isValid);
    }
}