package cn.flying.minio.core;

import cn.flying.minio.config.NodeConfig;
import cn.flying.minio.config.MinioProperties;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * S3客户端工厂类，用于创建和管理与MinIO服务器的S3兼容客户端连接
 * 支持多节点配置和连接池管理
 *
 * @author 王贝强
 * @create 2025-10-12
 */
@Slf4j
@Component
public class S3ClientFactory {

    @Resource
    private MinioProperties minioProperties;

    /**
     * S3客户端缓存，按节点名称存储
     */
    private final ConcurrentHashMap<String, AmazonS3> s3ClientCache = new ConcurrentHashMap<>();

    /**
     * 初始化所有配置的节点的S3客户端
     */
    @PostConstruct
    public void init() {
        log.info("初始化S3客户端工厂，节点数量: {}",
                minioProperties.getNodes() != null ? minioProperties.getNodes().size() : 0);

        if (minioProperties.getNodes() != null) {
            for (NodeConfig node : minioProperties.getNodes()) {
                try {
                    AmazonS3 client = createS3Client(node);
                    s3ClientCache.put(node.getName(), client);
                    log.info("成功创建节点 {} 的S3客户端，端点: {}", node.getName(), node.getEndpoint());
                } catch (Exception e) {
                    log.error("创建节点 {} 的S3客户端失败: {}", node.getName(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 创建S3客户端
     *
     * @param node MinIO节点配置
     * @return 配置好的S3客户端
     */
    private AmazonS3 createS3Client(NodeConfig node) {
        // 创建AWS凭证
        AWSCredentials credentials = new BasicAWSCredentials(
                node.getAccessKey(),
                node.getSecretKey()
        );

        // 配置客户端选项
        ClientConfiguration clientConfig = new ClientConfiguration();
        clientConfig.setSignerOverride("AWSS3V4SignerType");
        clientConfig.setConnectionTimeout(30000); // 30秒连接超时
        clientConfig.setSocketTimeout(60000);     // 60秒socket超时
        clientConfig.setMaxErrorRetry(3);         // 最多重试3次

        // 创建S3客户端
        return AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(
                    new AwsClientBuilder.EndpointConfiguration(
                        node.getEndpoint(),
                        "us-east-1" // MinIO通常不关心region，使用默认值
                    )
                )
                .withPathStyleAccessEnabled(true) // MinIO需要path-style访问
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withClientConfiguration(clientConfig)
                .build();
    }

    /**
     * 获取指定节点的S3客户端
     *
     * @param nodeName 节点名称
     * @return S3客户端，如果节点不存在则返回null
     */
    public AmazonS3 getS3Client(String nodeName) {
        AmazonS3 client = s3ClientCache.get(nodeName);
        if (client == null) {
            log.warn("未找到节点 {} 的S3客户端", nodeName);
            // 尝试动态创建客户端
            NodeConfig node = findNodeByName(nodeName);
            if (node != null) {
                try {
                    client = createS3Client(node);
                    s3ClientCache.put(nodeName, client);
                    log.info("动态创建了节点 {} 的S3客户端", nodeName);
                } catch (Exception e) {
                    log.error("动态创建节点 {} 的S3客户端失败: {}", nodeName, e.getMessage());
                }
            }
        }
        return client;
    }

    /**
     * 根据节点名称查找节点配置
     *
     * @param nodeName 节点名称
     * @return 节点配置，如果未找到则返回null
     */
    private NodeConfig findNodeByName(String nodeName) {
        if (minioProperties.getNodes() != null) {
            return minioProperties.getNodes().stream()
                    .filter(node -> nodeName.equals(node.getName()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    /**
     * 刷新指定节点的S3客户端
     *
     * @param nodeName 节点名称
     */
    public void refreshClient(String nodeName) {
        log.info("刷新节点 {} 的S3客户端", nodeName);

        // 关闭旧客户端
        AmazonS3 oldClient = s3ClientCache.remove(nodeName);
        if (oldClient != null) {
            try {
                oldClient.shutdown();
            } catch (Exception e) {
                log.warn("关闭旧S3客户端时出错: {}", e.getMessage());
            }
        }

        // 创建新客户端
        NodeConfig node = findNodeByName(nodeName);
        if (node != null) {
            try {
                AmazonS3 newClient = createS3Client(node);
                s3ClientCache.put(nodeName, newClient);
                log.info("成功刷新节点 {} 的S3客户端", nodeName);
            } catch (Exception e) {
                log.error("刷新节点 {} 的S3客户端失败: {}", nodeName, e.getMessage());
            }
        }
    }

    /**
     * 检查S3客户端是否可用
     *
     * @param nodeName 节点名称
     * @return true如果客户端可用，否则false
     */
    public boolean isClientAvailable(String nodeName) {
        AmazonS3 client = getS3Client(nodeName);
        if (client == null) {
            return false;
        }

        try {
            // 尝试列出桶来验证连接
            client.listBuckets();
            return true;
        } catch (Exception e) {
            log.warn("节点 {} 的S3客户端不可用: {}", nodeName, e.getMessage());
            return false;
        }
    }

    /**
     * 清理所有S3客户端连接
     */
    @PreDestroy
    public void cleanup() {
        log.info("关闭所有S3客户端连接");
        s3ClientCache.forEach((nodeName, client) -> {
            try {
                client.shutdown();
                log.debug("成功关闭节点 {} 的S3客户端", nodeName);
            } catch (Exception e) {
                log.warn("关闭节点 {} 的S3客户端时出错: {}", nodeName, e.getMessage());
            }
        });
        s3ClientCache.clear();
    }
}