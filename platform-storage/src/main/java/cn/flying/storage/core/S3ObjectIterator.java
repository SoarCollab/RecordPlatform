package cn.flying.storage.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.List;
import java.util.function.Consumer;

/**
 * 分页迭代 S3 对象的工具类。
 * 使用 {@code listObjectsV2} 的续传令牌逐页遍历，避免将所有键加载到内存中。
 *
 * <p>用法示例:
 * <pre>{@code
 * S3ObjectIterator.forEachPage(client, bucketName, page -> {
 *     for (S3Object obj : page) {
 *         // 处理每个对象
 *     }
 * });
 * }</pre>
 */
public final class S3ObjectIterator {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectIterator.class);

    private S3ObjectIterator() {
        // utility class
    }

    /**
     * 逐页遍历桶中的所有对象，将每页对象列表传递给消费者。
     * 自动处理分页续传令牌，跳过以 "/" 结尾的目录占位符。
     *
     * @param client     S3 客户端
     * @param bucketName 桶名称（同时用作节点名称）
     * @param pageConsumer 每页对象键列表的消费者
     * @throws NoSuchBucketException 当桶不存在时（调用方可自行捕获处理）
     */
    public static void forEachPage(S3Client client, String bucketName,
                                   Consumer<List<S3Object>> pageConsumer) {
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .build();

        ListObjectsV2Response listResponse;
        do {
            listResponse = client.listObjectsV2(listRequest);

            List<S3Object> objects = listResponse.contents().stream()
                    .filter(obj -> !obj.key().endsWith("/"))
                    .toList();

            if (!objects.isEmpty()) {
                pageConsumer.accept(objects);
            }

            listRequest = listRequest.toBuilder()
                    .continuationToken(listResponse.nextContinuationToken())
                    .build();
        } while (listResponse.isTruncated());
    }

    /**
     * 逐个遍历桶中的所有对象键，避免将所有键加载到内存中。
     * 自动处理分页续传令牌，跳过以 "/" 结尾的目录占位符。
     *
     * @param client      S3 客户端
     * @param bucketName  桶名称
     * @param keyConsumer 每个对象键的消费者
     */
    public static void forEachKey(S3Client client, String bucketName,
                                  Consumer<String> keyConsumer) {
        forEachPage(client, bucketName, page -> {
            for (S3Object obj : page) {
                keyConsumer.accept(obj.key());
            }
        });
    }

    /**
     * 检查桶是否存在。
     *
     * @param client     S3 客户端
     * @param bucketName 桶名称
     * @return true 如果桶存在
     */
    public static boolean bucketExists(S3Client client, String bucketName) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            return true;
        } catch (NoSuchBucketException e) {
            return false;
        }
    }
}
