package cn.flying.monitor.data.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Batch metrics data transfer object for high-performance data ingestion
 * Supports processing multiple metrics in a single request
 */
@Data
public class BatchMetricsDTO {
    
    @NotNull(message = "Metrics list cannot be null")
    @NotEmpty(message = "Metrics list cannot be empty")
    @Size(max = 1000, message = "Batch size cannot exceed 1000 metrics")
    @Valid
    private List<MetricsDataDTO> metrics;
    
    @JsonProperty("batch_id")
    private String batchId;
    
    @JsonProperty("compression_enabled")
    private Boolean compressionEnabled = false;
    
    @JsonProperty("batch_timestamp")
    private Long batchTimestamp;
    
    @JsonProperty("client_version")
    private String clientVersion;
    
    @JsonProperty("batch_checksum")
    private String batchChecksum;
}