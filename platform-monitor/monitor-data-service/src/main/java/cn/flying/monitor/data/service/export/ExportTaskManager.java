package cn.flying.monitor.data.service.export;

import cn.flying.monitor.data.dto.ExportResultDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 导出任务管理器，负责维护任务生命周期与过期清理
 */
@Component
public class ExportTaskManager {

    private final ConcurrentHashMap<String, ExportTaskRecord> tasks = new ConcurrentHashMap<>();
    private final Duration retention;

    public ExportTaskManager(@Value("${monitor.export.task-ttl-hours:24}") long ttlHours) {
        this.retention = Duration.ofHours(ttlHours <= 0 ? 24 : ttlHours);
    }

    public ExportTaskRecord createTask(ExportResultDTO result) {
        ExportTaskRecord record = new ExportTaskRecord(result);
        tasks.put(result.getExportId(), record);
        return record;
    }

    public Optional<ExportTaskRecord> findTask(String exportId) {
        ExportTaskRecord record = tasks.get(exportId);
        if (record != null && record.isExpired(retention)) {
            tasks.remove(exportId);
            return Optional.empty();
        }
        return Optional.ofNullable(record);
    }

    public boolean removeTask(String exportId) {
        return tasks.remove(exportId) != null;
    }

    public List<ExportTaskRecord> listTasks() {
        cleanupExpired();
        return tasks.values().stream()
                .sorted(Comparator.comparing(ExportTaskRecord::getCreatedAt).reversed())
                .toList();
    }

    public void cleanupExpired() {
        tasks.entrySet().removeIf(entry -> entry.getValue().isExpired(retention));
    }
}
