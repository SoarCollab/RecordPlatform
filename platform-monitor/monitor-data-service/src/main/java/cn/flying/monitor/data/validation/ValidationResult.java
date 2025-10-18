package cn.flying.monitor.data.validation;

import lombok.Data;

import java.util.List;

/**
 * Validation result for individual metrics
 */
@Data
public class ValidationResult {
    private boolean valid;
    private List<String> errors;
    private List<String> warnings;
    private double dataQualityScore;
}