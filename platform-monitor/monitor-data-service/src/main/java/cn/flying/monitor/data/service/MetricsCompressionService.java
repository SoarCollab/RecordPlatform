package cn.flying.monitor.data.service;

import cn.flying.monitor.data.dto.BatchMetricsDTO;
import cn.flying.monitor.data.dto.MetricsDataDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Service for compressing and decompressing metrics data
 * Optimizes network transmission and storage
 */
@Service
@Slf4j
public class MetricsCompressionService {
    
    private final ObjectMapper objectMapper;
    
    public MetricsCompressionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * Compresses metrics data using GZIP compression
     */
    public byte[] compressMetrics(MetricsDataDTO metrics) throws IOException {
        byte[] jsonData = objectMapper.writeValueAsBytes(metrics);
        return compressData(jsonData);
    }
    
    /**
     * Compresses batch metrics data
     */
    public byte[] compressBatchMetrics(BatchMetricsDTO batchMetrics) throws IOException {
        byte[] jsonData = objectMapper.writeValueAsBytes(batchMetrics);
        return compressData(jsonData);
    }
    
    /**
     * Decompresses metrics data
     */
    public MetricsDataDTO decompressMetrics(byte[] compressedData) throws IOException {
        byte[] jsonData = decompressData(compressedData);
        return objectMapper.readValue(jsonData, MetricsDataDTO.class);
    }
    
    /**
     * Decompresses batch metrics data
     */
    public BatchMetricsDTO decompressBatchMetrics(byte[] compressedData) throws IOException {
        byte[] jsonData = decompressData(compressedData);
        return objectMapper.readValue(jsonData, BatchMetricsDTO.class);
    }
    
    /**
     * Calculates compression ratio for metrics data
     */
    public double calculateCompressionRatio(MetricsDataDTO metrics) {
        try {
            byte[] originalData = objectMapper.writeValueAsBytes(metrics);
            byte[] compressedData = compressData(originalData);
            
            return (double) compressedData.length / originalData.length;
        } catch (IOException e) {
            log.warn("Failed to calculate compression ratio", e);
            return 1.0; // No compression
        }
    }
    
    /**
     * Determines if compression is beneficial for the given data
     */
    public boolean shouldCompress(MetricsDataDTO metrics) {
        try {
            byte[] originalData = objectMapper.writeValueAsBytes(metrics);
            
            // Only compress if data is larger than 1KB
            if (originalData.length < 1024) {
                return false;
            }
            
            // Test compression ratio
            double ratio = calculateCompressionRatio(metrics);
            
            // Compress if we can achieve at least 20% reduction
            return ratio < 0.8;
        } catch (Exception e) {
            log.warn("Failed to determine compression benefit", e);
            return false;
        }
    }
    
    /**
     * Optimizes metrics data for transmission
     */
    public OptimizedMetricsData optimizeForTransmission(MetricsDataDTO metrics) {
        OptimizedMetricsData optimized = new OptimizedMetricsData();
        
        try {
            if (shouldCompress(metrics)) {
                byte[] compressedData = compressMetrics(metrics);
                double compressionRatio = calculateCompressionRatio(metrics);
                
                optimized.setData(compressedData);
                optimized.setCompressed(true);
                optimized.setCompressionRatio(compressionRatio);
                optimized.setOriginalSize(objectMapper.writeValueAsBytes(metrics).length);
                optimized.setCompressedSize(compressedData.length);
            } else {
                byte[] originalData = objectMapper.writeValueAsBytes(metrics);
                optimized.setData(originalData);
                optimized.setCompressed(false);
                optimized.setCompressionRatio(1.0);
                optimized.setOriginalSize(originalData.length);
                optimized.setCompressedSize(originalData.length);
            }
        } catch (IOException e) {
            log.error("Failed to optimize metrics data", e);
            throw new RuntimeException("Metrics optimization failed", e);
        }
        
        return optimized;
    }
    
    private byte[] compressData(byte[] data) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            
            gzipOut.write(data);
            gzipOut.finish();
            
            return baos.toByteArray();
        }
    }
    
    private byte[] decompressData(byte[] compressedData) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
             GZIPInputStream gzipIn = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipIn.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            
            return baos.toByteArray();
        }
    }
    
    /**
     * Container for optimized metrics data
     */
    public static class OptimizedMetricsData {
        private byte[] data;
        private boolean compressed;
        private double compressionRatio;
        private int originalSize;
        private int compressedSize;
        
        // Getters and setters
        public byte[] getData() { return data; }
        public void setData(byte[] data) { this.data = data; }
        
        public boolean isCompressed() { return compressed; }
        public void setCompressed(boolean compressed) { this.compressed = compressed; }
        
        public double getCompressionRatio() { return compressionRatio; }
        public void setCompressionRatio(double compressionRatio) { this.compressionRatio = compressionRatio; }
        
        public int getOriginalSize() { return originalSize; }
        public void setOriginalSize(int originalSize) { this.originalSize = originalSize; }
        
        public int getCompressedSize() { return compressedSize; }
        public void setCompressedSize(int compressedSize) { this.compressedSize = compressedSize; }
        
        public int getSavedBytes() { return originalSize - compressedSize; }
        public double getSavedPercentage() { return (1.0 - compressionRatio) * 100.0; }
    }
}