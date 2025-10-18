package cn.flying.monitor.data.service;

import cn.flying.monitor.data.dto.QueryRequestDTO;
import cn.flying.monitor.data.dto.QueryResultDTO;
import cn.flying.monitor.data.dto.AggregationResultDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Enhanced caching service for query optimization
 */
public interface QueryCacheService {
    
    /**
     * Cache historical metrics query results
     */
    void cacheHistoricalMetrics(String cacheKey, Page<QueryResultDTO> results, long ttlSeconds);
    
    /**
     * Get cached historical metrics
     */
    Optional<Page<QueryResultDTO>> getCachedHistoricalMetrics(String cacheKey);
    
    /**
     * Cache real-time metrics
     */
    void cacheRealTimeMetrics(String clientId, QueryResultDTO result);
    
    /**
     * Get cached real-time metrics
     */
    Optional<QueryResultDTO> getCachedRealTimeMetrics(String clientId);
    
    /**
     * Cache aggregation results
     */
    void cacheAggregationResults(String cacheKey, AggregationResultDTO result, long ttlSeconds);
    
    /**
     * Get cached aggregation results
     */
    Optional<AggregationResultDTO> getCachedAggregationResults(String cacheKey);
    
    /**
     * Cache query statistics
     */
    void cacheQueryStatistics(String cacheKey, Map<String, Object> statistics, long ttlSeconds);
    
    /**
     * Get cached query statistics
     */
    Optional<Map<String, Object>> getCachedQueryStatistics(String cacheKey);
    
    /**
     * Cache client metadata
     */
    void cacheClientMetadata(String clientId, Map<String, Object> metadata);
    
    /**
     * Get cached client metadata
     */
    Optional<Map<String, Object>> getCachedClientMetadata(String clientId);
    
    /**
     * Warm cache with frequently accessed data
     */
    void warmCache(List<String> clientIds, Instant start, Instant end);
    
    /**
     * Invalidate cache for specific client
     */
    void invalidateClientCache(String clientId);
    
    /**
     * Invalidate cache by pattern
     */
    void invalidateCacheByPattern(String pattern);
    
    /**
     * Get cache statistics and hit rates
     */
    Map<String, Object> getCacheStatistics();
    
    /**
     * Generate cache key for query request
     */
    String generateCacheKey(QueryRequestDTO request, Pageable pageable);
    
    /**
     * Generate cache key for aggregation request
     */
    String generateAggregationCacheKey(String clientId, List<String> metricNames, 
                                     Instant start, Instant end, String aggregationType);
    
    /**
     * Check if cache key exists
     */
    boolean isCached(String cacheKey);
    
    /**
     * Get cache TTL for key
     */
    long getCacheTtl(String cacheKey);
    
    /**
     * Set cache with custom TTL
     */
    void setCache(String cacheKey, Object value, long ttlSeconds);
    
    /**
     * Preload cache for upcoming queries
     */
    void preloadCache(List<QueryRequestDTO> upcomingQueries);
    
    /**
     * Clean expired cache entries
     */
    void cleanExpiredEntries();
}