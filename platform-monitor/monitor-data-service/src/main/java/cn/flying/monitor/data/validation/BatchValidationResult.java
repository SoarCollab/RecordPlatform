package cn.flying.monitor.data.validation;

import lombok.Data;

import java.util.List;

/**
 * Validation result for batch metrics processing
 */
@Data
public class BatchValidationResult {
    private boolean valid;
    private List<ValidationResult> individualResults;
    private List<String> batchErrors;
    private int totalMetrics;
    private int validMetrics;
    
    public int getInvalidMetrics() {
        return totalMetrics - validMetrics;
    }
    
    public double getValidationSuccessRate() {
        return totalMetrics > 0 ? (double) validMetrics / totalMetrics * 100.0 : 0.0;
    }
}