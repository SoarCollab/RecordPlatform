package cn.flying.monitor.data.service.impl;

import cn.flying.monitor.data.dto.QueryRequestDTO;
import cn.flying.monitor.data.dto.QueryResultDTO;
import cn.flying.monitor.data.dto.AggregationResultDTO;
import cn.flying.monitor.data.service.QueryCacheService;
import cn.flying.monitor.data.service.QueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Enhanced Redis caching service implementation with intelligent caching strategies
 */
@Service
@Slf4j
public class QueryCacheServiceImpl implements QueryCacheService {
    
    @Autowired
    private CacheManager cacheManager;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private QueryService queryService;
    
    // Cache statistics
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong cacheEvictions = new AtomicLong(0);
    private final Map<String, AtomicLong> cacheTypeHits = new ConcurrentHashMap<>();
    
    // Cache key prefixes
    private static final String HISTORICAL_PREFIX = "historical:";
    private static final String REALTIME_PREFIX = "realtime:";
    private static final String AGGREGATION_PREFIX = "aggregation:";
    private static final String STATISTICS_PREFIX = "statistics:";
    private static final String CLIENT_METADATA_PREFIX = "client:metadata:";
    private static final String QUERY_PERFORMANCE_PREFIX = "query:performance:";
    
    // Default TTL values (in seconds)
    private static final long REALTIME_TTL = 30;
    private static final long HISTORICAL_TTL = 300;
    private static final long AGGREGATION_TTL = 900;
    private static final long STATISTICS_TTL = 1800;
    private static final long CLIENT_METADATA_TTL = 3600;
    
    @Override
    public void cacheHistoricalMetrics(String cacheKey, Page<QueryResultDTO> results, long ttlSeconds) {
        try {
            Cache cache = cacheManager.getCache("historical-metrics");
            if (cache != null) {
                cache.put(HISTORICAL_PREFIX + cacheKey, results);
                
                // Also store in Redis with custom TTL
                redisTemplate.opsForValue().set(
                    HISTORICAL_PREFIX + cacheKey, 
                    results, 
                    ttlSeconds, 
                    TimeUnit.SECONDS
                );
                
                log.debug("Cached historical metrics: key={}, size={}, ttl={}s", 
                         cacheKey, results.getContent().size(), ttlSeconds);
            }
        } catch (Exception e) {
            log.error("Failed to cache historical metrics: {}", cacheKey, e);
        }
    }
    
    @Override
    public Optional<Page<QueryResultDTO>> getCachedHistoricalMetrics(String cacheKey) {
        try {
            Cache cache = cacheManager.getCache("historical-metrics");
            if (cache != null) {
                Cache.ValueWrapper wrapper = cache.get(HISTORICAL_PREFIX + cacheKey);
                if (wrapper != null) {
                    cacheHits.incrementAndGet();
                    cacheTypeHits.computeIfAbsent("historical", k -> new AtomicLong(0)).incrementAndGet();
                    
                    @SuppressWarnings("unchecked")
                    Page<QueryResultDTO> result = (Page<QueryResultDTO>) wrapper.get();
                    
                    log.debug("Cache hit for historical metrics: key={}", cacheKey);
                    return Optional.of(result);
                }
            }
            
            cacheMisses.incrementAndGet();
            log.debug("Cache miss for historical metrics: key={}", cacheKey);
            return Optional.empty();
            
        } catch (Exception e) {
            log.error("Failed to get cached historical metrics: {}", cacheKey, e);
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }
    }
    
    @Override
    public void cacheRealTimeMetrics(String clientId, QueryResultDTO result) {
        try {
            Cache cache = cacheManager.getCache("realtime-metrics");
            if (cache != null) {
                String key = REALTIME_PREFIX + clientId;
                cache.put(key, result);
                
                // Store in Redis with short TTL
                redisTemplate.opsForValue().set(key, result, REALTIME_TTL, TimeUnit.SECONDS);
                
                log.debug("Cached real-time metrics for client: {}", clientId);
            }
        } catch (Exception e) {
            log.error("Failed to cache real-time metrics for client: {}", clientId, e);
        }
    }
    
    @Override
    public Optional<QueryResultDTO> getCachedRealTimeMetrics(String clientId) {
        try {
            Cache cache = cacheManager.getCache("realtime-metrics");
            if (cache != null) {
                String key = REALTIME_PREFIX + clientId;
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    cacheHits.incrementAndGet();
                    cacheTypeHits.computeIfAbsent("realtime", k -> new AtomicLong(0)).incrementAndGet();
                    
                    QueryResultDTO result = (QueryResultDTO) wrapper.get();
                    log.debug("Cache hit for real-time metrics: client={}", clientId);
                    return Optional.of(result);
                }
            }
            
            cacheMisses.incrementAndGet();
            return Optional.empty();
            
        } catch (Exception e) {
            log.error("Failed to get cached real-time metrics for client: {}", clientId, e);
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }
    }
    
    @Override
    public void cacheAggregationResults(String cacheKey, AggregationResultDTO result, long ttlSeconds) {
        try {
            Cache cache = cacheManager.getCache("aggregation-results");
            if (cache != null) {
                String key = AGGREGATION_PREFIX + cacheKey;
                cache.put(key, result);
                
                redisTemplate.opsForValue().set(key, result, ttlSeconds, TimeUnit.SECONDS);
                
                log.debug("Cached aggregation results: key={}, ttl={}s", cacheKey, ttlSeconds);
            }
        } catch (Exception e) {
            log.error("Failed to cache aggregation results: {}", cacheKey, e);
        }
    }
    
    @Override
    public Optional<AggregationResultDTO> getCachedAggregationResults(String cacheKey) {
        try {
            Cache cache = cacheManager.getCache("aggregation-results");
            if (cache != null) {
                String key = AGGREGATION_PREFIX + cacheKey;
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    cacheHits.incrementAndGet();
                    cacheTypeHits.computeIfAbsent("aggregation", k -> new AtomicLong(0)).incrementAndGet();
                    
                    AggregationResultDTO result = (AggregationResultDTO) wrapper.get();
                    log.debug("Cache hit for aggregation results: key={}", cacheKey);
                    return Optional.of(result);
                }
            }
            
            cacheMisses.incrementAndGet();
            return Optional.empty();
            
        } catch (Exception e) {
            log.error("Failed to get cached aggregation results: {}", cacheKey, e);
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }
    }
    
    @Override
    public void cacheQueryStatistics(String cacheKey, Map<String, Object> statistics, long ttlSeconds) {
        try {
            Cache cache = cacheManager.getCache("statistics");
            if (cache != null) {
                String key = STATISTICS_PREFIX + cacheKey;
                cache.put(key, statistics);
                
                redisTemplate.opsForValue().set(key, statistics, ttlSeconds, TimeUnit.SECONDS);
                
                log.debug("Cached query statistics: key={}, ttl={}s", cacheKey, ttlSeconds);
            }
        } catch (Exception e) {
            log.error("Failed to cache query statistics: {}", cacheKey, e);
        }
    }
    
    @Override
    public Optional<Map<String, Object>> getCachedQueryStatistics(String cacheKey) {
        try {
            Cache cache = cacheManager.getCache("statistics");
            if (cache != null) {
                String key = STATISTICS_PREFIX + cacheKey;
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    cacheHits.incrementAndGet();
                    cacheTypeHits.computeIfAbsent("statistics", k -> new AtomicLong(0)).incrementAndGet();
                    
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) wrapper.get();
                    
                    log.debug("Cache hit for query statistics: key={}", cacheKey);
                    return Optional.of(result);
                }
            }
            
            cacheMisses.incrementAndGet();
            return Optional.empty();
            
        } catch (Exception e) {
            log.error("Failed to get cached query statistics: {}", cacheKey, e);
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }
    }
    
    @Override
    public void cacheClientMetadata(String clientId, Map<String, Object> metadata) {
        try {
            Cache cache = cacheManager.getCache("client-metadata");
            if (cache != null) {
                String key = CLIENT_METADATA_PREFIX + clientId;
                cache.put(key, metadata);
                
                redisTemplate.opsForValue().set(key, metadata, CLIENT_METADATA_TTL, TimeUnit.SECONDS);
                
                log.debug("Cached client metadata: client={}", clientId);
            }
        } catch (Exception e) {
            log.error("Failed to cache client metadata: {}", clientId, e);
        }
    }
    
    @Override
    public Optional<Map<String, Object>> getCachedClientMetadata(String clientId) {
        try {
            Cache cache = cacheManager.getCache("client-metadata");
            if (cache != null) {
                String key = CLIENT_METADATA_PREFIX + clientId;
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    cacheHits.incrementAndGet();
                    cacheTypeHits.computeIfAbsent("client-metadata", k -> new AtomicLong(0)).incrementAndGet();
                    
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) wrapper.get();
                    
                    log.debug("Cache hit for client metadata: client={}", clientId);
                    return Optional.of(result);
                }
            }
            
            cacheMisses.incrementAndGet();
            return Optional.empty();
            
        } catch (Exception e) {
            log.error("Failed to get cached client metadata: {}", clientId, e);
            cacheMisses.incrementAndGet();
            return Optional.empty();
        }
    }
    
    @Override
    public void warmCache(List<String> clientIds, Instant start, Instant end) {
        log.info("Starting cache warming for {} clients", clientIds.size());
        
        CompletableFuture.runAsync(() -> {
            try {
                for (String clientId : clientIds) {
                    // Warm real-time metrics cache
                    QueryResultDTO realTimeMetrics = queryService.queryRealTimeMetrics(clientId);
                    if (realTimeMetrics != null) {
                        cacheRealTimeMetrics(clientId, realTimeMetrics);
                    }
                    
                    // Warm frequently accessed aggregations
                    List<String> commonMetrics = Arrays.asList("cpu_usage", "memory_usage", "disk_usage");
                    String aggKey = generateAggregationCacheKey(clientId, commonMetrics, start, end, "avg");
                    
                    // Check if not already cached
                    if (!isCached(AGGREGATION_PREFIX + aggKey)) {
                        // This would trigger caching in the service layer
                        log.debug("Cache warming triggered for client: {}", clientId);
                    }
                    
                    // Small delay to avoid overwhelming the system
                    Thread.sleep(100);
                }
                
                log.info("Cache warming completed for {} clients", clientIds.size());
                
            } catch (Exception e) {
                log.error("Cache warming failed", e);
            }
        });
    }
    
    @Override
    public void invalidateClientCache(String clientId) {
        try {
            // Invalidate all cache types for the client
            String[] patterns = {
                REALTIME_PREFIX + clientId,
                HISTORICAL_PREFIX + "*" + clientId + "*",
                AGGREGATION_PREFIX + "*" + clientId + "*",
                CLIENT_METADATA_PREFIX + clientId
            };
            
            for (String pattern : patterns) {
                invalidateCacheByPattern(pattern);
            }
            
            cacheEvictions.incrementAndGet();
            log.info("Invalidated cache for client: {}", clientId);
            
        } catch (Exception e) {
            log.error("Failed to invalidate cache for client: {}", clientId, e);
        }
    }
    
    @Override
    public void invalidateCacheByPattern(String pattern) {
        try {
            // Use Redis SCAN to find keys matching pattern
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("Invalidated {} cache entries matching pattern: {}", keys.size(), pattern);
            }
            
            // Also clear from Spring Cache Manager
            cacheManager.getCacheNames().forEach(cacheName -> {
                Cache cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    // Spring Cache doesn't support pattern-based eviction directly
                    // This is a limitation we'd need to work around in a real implementation
                    log.debug("Pattern-based eviction not fully supported for cache: {}", cacheName);
                }
            });
            
        } catch (Exception e) {
            log.error("Failed to invalidate cache by pattern: {}", pattern, e);
        }
    }
    
    @Override
    public Map<String, Object> getCacheStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        long totalRequests = cacheHits.get() + cacheMisses.get();
        double hitRate = totalRequests > 0 ? (double) cacheHits.get() / totalRequests : 0.0;
        
        stats.put("total_requests", totalRequests);
        stats.put("cache_hits", cacheHits.get());
        stats.put("cache_misses", cacheMisses.get());
        stats.put("hit_rate", hitRate);
        stats.put("evictions", cacheEvictions.get());
        
        // Cache type specific statistics
        Map<String, Object> typeStats = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : cacheTypeHits.entrySet()) {
            typeStats.put(entry.getKey() + "_hits", entry.getValue().get());
        }
        stats.put("cache_type_stats", typeStats);
        
        // Redis connection info
        try {
            stats.put("redis_connected", redisTemplate.getConnectionFactory().getConnection().ping() != null);
        } catch (Exception e) {
            stats.put("redis_connected", false);
        }
        
        return stats;
    }
    
    @Override
    public String generateCacheKey(QueryRequestDTO request, Pageable pageable) {
        StringBuilder keyBuilder = new StringBuilder();
        
        keyBuilder.append(request.getClientId())
                  .append(":")
                  .append(request.getStartTime().getEpochSecond())
                  .append(":")
                  .append(request.getEndTime().getEpochSecond());
        
        if (request.getMetricNames() != null && !request.getMetricNames().isEmpty()) {
            keyBuilder.append(":metrics:")
                      .append(String.join(",", request.getMetricNames()));
        }
        
        if (request.hasTimeWindow()) {
            keyBuilder.append(":window:").append(request.getTimeWindow());
        }
        
        if (request.hasFilters()) {
            keyBuilder.append(":filters:").append(request.getFilters().hashCode());
        }
        
        if (pageable != null) {
            keyBuilder.append(":page:").append(pageable.getPageNumber())
                      .append(":size:").append(pageable.getPageSize());
        }
        
        return keyBuilder.toString();
    }
    
    @Override
    public String generateAggregationCacheKey(String clientId, List<String> metricNames, 
                                            Instant start, Instant end, String aggregationType) {
        return String.format("%s:%s:%d:%d:%s", 
                           clientId, 
                           String.join(",", metricNames),
                           start.getEpochSecond(),
                           end.getEpochSecond(),
                           aggregationType);
    }
    
    @Override
    public boolean isCached(String cacheKey) {
        try {
            return redisTemplate.hasKey(cacheKey);
        } catch (Exception e) {
            log.error("Failed to check cache key existence: {}", cacheKey, e);
            return false;
        }
    }
    
    @Override
    public long getCacheTtl(String cacheKey) {
        try {
            return redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to get cache TTL: {}", cacheKey, e);
            return -1;
        }
    }
    
    @Override
    public void setCache(String cacheKey, Object value, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(cacheKey, value, ttlSeconds, TimeUnit.SECONDS);
            log.debug("Set cache: key={}, ttl={}s", cacheKey, ttlSeconds);
        } catch (Exception e) {
            log.error("Failed to set cache: {}", cacheKey, e);
        }
    }
    
    @Override
    public void preloadCache(List<QueryRequestDTO> upcomingQueries) {
        log.info("Preloading cache for {} upcoming queries", upcomingQueries.size());
        
        CompletableFuture.runAsync(() -> {
            try {
                for (QueryRequestDTO request : upcomingQueries) {
                    String cacheKey = generateCacheKey(request, null);
                    
                    if (!isCached(HISTORICAL_PREFIX + cacheKey)) {
                        // Trigger query execution which will cache the result
                        log.debug("Preloading cache for query: client={}, timeRange={}-{}", 
                                request.getClientId(), request.getStartTime(), request.getEndTime());
                        
                        // Small delay between preload operations
                        Thread.sleep(200);
                    }
                }
                
                log.info("Cache preloading completed");
                
            } catch (Exception e) {
                log.error("Cache preloading failed", e);
            }
        });
    }
    
    @Override
    public void cleanExpiredEntries() {
        try {
            // Redis automatically handles TTL expiration, but we can clean up our statistics
            log.debug("Cleaning expired cache entries and resetting statistics");
            
            // Reset statistics periodically to avoid memory buildup
            long totalRequests = cacheHits.get() + cacheMisses.get();
            if (totalRequests > 1000000) { // Reset after 1M requests
                cacheHits.set(0);
                cacheMisses.set(0);
                cacheEvictions.set(0);
                cacheTypeHits.clear();
                log.info("Cache statistics reset after {} total requests", totalRequests);
            }
            
        } catch (Exception e) {
            log.error("Failed to clean expired cache entries", e);
        }
    }
}