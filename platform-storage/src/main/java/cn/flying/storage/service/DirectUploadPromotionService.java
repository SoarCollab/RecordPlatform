package cn.flying.storage.service;

import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.S3ClientManager;
import cn.flying.storage.core.S3ClientManager.TopologyLease;
import cn.flying.storage.core.S3Monitor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 以条件式对象复制或严格有界流式转发完成单个 direct-upload 分片的可信提升。
 */
@Service
public class DirectUploadPromotionService {

    private static final Logger log = LoggerFactory.getLogger(DirectUploadPromotionService.class);

    private static final String METADATA_FILE_HASH = "file-hash";
    private static final String METADATA_TENANT_ID = "tenant-id";
    private static final String METADATA_CHECKSUM_ALGORITHM = "checksum-algorithm";
    private static final String METADATA_PLAIN_HASH = "plain-hash";
    private static final String METADATA_CIPHER_HASH = "cipher-hash";
    private static final String CHECKSUM_ALGORITHM_SHA256 = "SHA-256";
    private static final String HASH_PREFIX_SHA256 = "sha256:";
    private static final int MAX_DIRECT_UPLOAD_ETAG_LENGTH = 255;
    private static final Pattern PHYSICAL_STORAGE_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final ScheduledThreadPoolExecutor DEADLINE_ABORT_SCHEDULER =
            createDeadlineAbortScheduler();

    private final S3ClientManager clientManager;
    private final S3Monitor s3Monitor;
    private final StorageProperties storageProperties;
    private final ConsistencyRepairService consistencyRepairService;
    private final DegradedWriteTracker degradedWriteTracker;
    private final DirectUploadLockManager lockManager;
    private final DirectUploadStagingTracker stagingTracker;
    private final DirectUploadPromotionReceiptStore receiptStore;
    private final DirectUploadOperationIntentStore operationIntentStore;
    private final ExecutorService uploadExecutor;
    private final MeterRegistry meterRegistry;

    private final Cache<String, Boolean> bucketExistenceCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .maximumSize(256)
            .build();

    /**
     * 创建可从延迟队列移除已取消任务的守护调度器，避免正常闭流后仍保留响应对象到原截止点。
     *
     * @return direct-upload 响应流截止任务调度器
     */
    private static ScheduledThreadPoolExecutor createDeadlineAbortScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "direct-upload-deadline-abort");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    /**
     * 注入对象存储依赖和现有有界上传执行器。
     */
    public DirectUploadPromotionService(
            S3ClientManager clientManager,
            S3Monitor s3Monitor,
            StorageProperties storageProperties,
            ConsistencyRepairService consistencyRepairService,
            DegradedWriteTracker degradedWriteTracker,
            DirectUploadLockManager lockManager,
            DirectUploadStagingTracker stagingTracker,
            DirectUploadPromotionReceiptStore receiptStore,
            DirectUploadOperationIntentStore operationIntentStore,
            @Qualifier("storageUploadExecutor") ExecutorService uploadExecutor,
            MeterRegistry meterRegistry
    ) {
        this.clientManager = clientManager;
        this.s3Monitor = s3Monitor;
        this.storageProperties = storageProperties;
        this.consistencyRepairService = consistencyRepairService;
        this.degradedWriteTracker = degradedWriteTracker;
        this.lockManager = lockManager;
        this.stagingTracker = stagingTracker;
        this.receiptStore = receiptStore;
        this.operationIntentStore = operationIntentStore;
        this.uploadExecutor = uploadExecutor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 在分片锁内校验 staging 内容并提升到满足 quorum 的最终副本。
     *
     * @param part 后端可信元数据重建的分片描述
     * @param aggregateDigest 按分片顺序更新的整文件 SHA-256
     * @return 可信 final 对象大小和 ETag
     */
    public DirectUploadPromotionResult promote(
            DirectUploadPartDescriptor part,
            DirectUploadDigestAccumulator aggregateDigest
    ) {
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(aggregateDigest, "aggregateDigest");
        try (TopologyLease topology = clientManager.acquireTopologyLease()) {
            validateIndependentReplicaTargets(
                    part.targetNodes(),
                    part.requiredQuorum(),
                    "current topology",
                    topology
            );
            try (DirectUploadLockManager.LockHandle ignored = lockManager.acquire(part.stagingDescriptor())) {
                DirectUploadOperationIntentStore.OperationIntent intent =
                        operationIntentStore.beginComplete(part);
                return promoteLocked(part, aggregateDigest, intent, topology);
            }
        }
    }

    /**
     * 在与 complete 相同的分片锁内幂等删除 staging 对象。
     *
     * @param descriptor staging 对象身份
     */
    public void abort(DirectUploadStagingDescriptor descriptor) {
        try (DirectUploadLockManager.LockHandle ignored = lockManager.acquire(descriptor)) {
            DirectUploadOperationIntentStore.OperationIntent intent =
                    operationIntentStore.beginAbort(descriptor);
            try (TopologyLease topology = clientManager.acquireTopologyLease()) {
                S3Client client = null;
                RuntimeException providerFailure = null;
                try {
                    client = topology.getClient(descriptor.nodeName());
                    if (client == null) {
                        providerFailure = new IllegalStateException(
                                "storage client unavailable: " + descriptor.nodeName());
                    }
                } catch (RuntimeException e) {
                    providerFailure = e;
                }
                cleanupStagingObjectsAndRetain(descriptor, client, providerFailure, intent);
            }
            incrementOperationMetric("abort", "success");
        } catch (RuntimeException e) {
            incrementOperationMetric("abort", "failure");
            throw e;
        }
    }

    /**
     * 执行已持锁的首次提升或 final-object retry。
     */
    private DirectUploadPromotionResult promoteLocked(
            DirectUploadPartDescriptor part,
            DirectUploadDigestAccumulator aggregateDigest,
            DirectUploadOperationIntentStore.OperationIntent intent,
            TopologyLease topology
    ) {
        Optional<DirectUploadPromotionReceiptStore.PromotionReceipt> receipt =
                receiptStore.findValidated(part);
        if (receipt.isPresent()) {
            return completeFromFinalReplicas(
                    part,
                    aggregateDigest,
                    receipt,
                    false,
                    true,
                    intent,
                    topology
            );
        }

        S3Client sourceClient = topology.getClient(part.sourceNode());
        if (sourceClient == null) {
            log.warn("直传 staging 源节点客户端不可用，尝试从 final 副本完成: node={}, partIndex={}",
                    part.sourceNode(), part.partIndex());
            return completeFromFinalReplicas(
                    part, aggregateDigest, receipt, false, false, intent, topology);
        }

        long verificationDeadline = newOperationDeadline();
        Optional<HeadObjectResponse> existingSealed = verifyExistingSealed(
                part,
                sourceClient,
                aggregateDigest,
                verificationDeadline,
                intent
        );
        if (existingSealed.isPresent()) {
            return promoteVerifiedSealed(part, sourceClient, existingSealed.get(), intent, topology);
        }

        HeadObjectResponse stagingHead;
        try {
            stagingHead = sourceClient.headObject(HeadObjectRequest.builder()
                    .bucket(part.sourceNode())
                    .key(part.stagingObjectName())
                    .overrideConfiguration(requestOverrideConfiguration(
                            verificationDeadline,
                            "staging HEAD",
                            part.partIndex()))
                    .build());
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            return completeFromFinalReplicas(
                    part, aggregateDigest, receipt, true, false, intent, topology);
        } catch (S3Exception e) {
            if (isMissingObject(e)) {
                return completeFromFinalReplicas(
                        part, aggregateDigest, receipt, true, false, intent, topology);
            }
            log.warn("读取直传 staging 元数据失败，尝试从 final 副本完成: node={}, partIndex={}",
                    part.sourceNode(), part.partIndex(), e);
            return completeFromFinalReplicas(
                    part, aggregateDigest, receipt, false, false, intent, topology);
        } catch (SdkException e) {
            log.warn("直传 staging 源节点不可达，尝试从 final 副本完成: node={}, partIndex={}",
                    part.sourceNode(), part.partIndex(), e);
            return completeFromFinalReplicas(
                    part, aggregateDigest, receipt, false, false, intent, topology);
        }

        validateStagingHead(part, stagingHead);
        sealPublicStaging(part, sourceClient, stagingHead, verificationDeadline, intent);
        HeadObjectResponse sealedHead = headSealedObject(part, sourceClient, verificationDeadline);
        validateSealedHead(part, sealedHead);
        aggregateDigest.commit(streamAndVerifyObject(
                sourceClient,
                part.sourceNode(),
                part.stagingDescriptor().sealedObjectName(),
                sealedHead,
                part,
                aggregateDigest,
                verificationDeadline
        ));
        return promoteVerifiedSealed(part, sourceClient, sealedHead, intent, topology);
    }

    /**
     * 优先复核进程崩溃前已建立的 sealed；有效时直接复用且完全不读取 public staging。
     *
     * <p>内容/大小不匹配的 sealed 会在分片锁内先删除，再允许从当前 public staging 重建；
     * 网络或 provider 状态未知时失败关闭，避免覆盖一个可能仍有效的 sealed。</p>
     */
    private Optional<HeadObjectResponse> verifyExistingSealed(
            DirectUploadPartDescriptor part,
            S3Client sourceClient,
            DirectUploadDigestAccumulator aggregateDigest,
            long verificationDeadline,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        HeadObjectResponse sealedHead;
        try {
            sealedHead = headSealedObject(part, sourceClient, verificationDeadline);
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (isMissingObject(e)) {
                return Optional.empty();
            }
            throw e;
        }

        try {
            validateSealedHead(part, sealedHead);
            MessageDigest candidate = streamAndVerifyObject(
                    sourceClient,
                    part.sourceNode(),
                    part.stagingDescriptor().sealedObjectName(),
                    sealedHead,
                    part,
                    aggregateDigest,
                    verificationDeadline
            );
            aggregateDigest.commit(candidate);
            incrementOperationMetric("sealed", "reused");
            return Optional.of(sealedHead);
        } catch (IllegalArgumentException invalidSealed) {
            operationIntentStore.verify(intent);
            deleteStagingObject(
                    sourceClient,
                    part.sourceNode(),
                    part.stagingDescriptor().sealedObjectName(),
                    verificationDeadline,
                    part.partIndex()
            );
            incrementOperationMetric("sealed", "invalid_rebuild");
            log.warn("既有 direct-upload sealed 内容无效，已先删除并允许从 public staging 重建: partIndex={}",
                    part.partIndex(), invalidSealed);
            return Optional.empty();
        }
    }

    /**
     * 读取 storage-only sealed 的元数据；调用方负责区分不存在与 provider 故障。
     */
    private HeadObjectResponse headSealedObject(
            DirectUploadPartDescriptor part,
            S3Client sourceClient,
            long deadlineNanos
    ) {
        return sourceClient.headObject(HeadObjectRequest.builder()
                .bucket(part.sourceNode())
                .key(part.stagingDescriptor().sealedObjectName())
                .overrideConfiguration(requestOverrideConfiguration(
                        deadlineNanos,
                        "sealed HEAD",
                        part.partIndex()))
                .build());
    }

    /**
     * 校验 sealed 的基础 provider 合同；内容可信度由随后 bounded SHA-256 决定。
     */
    private void validateSealedHead(DirectUploadPartDescriptor part, HeadObjectResponse sealedHead) {
        if (sealedHead.contentLength() == null || sealedHead.contentLength() != part.size()) {
            throw new IllegalArgumentException("direct-upload sealed object size mismatch");
        }
        if (sealedHead.eTag() == null || sealedHead.eTag().isBlank()) {
            throw new IllegalArgumentException("direct-upload sealed object ETag is missing");
        }
    }

    /**
     * 以 public staging ETag 为条件建立 storage-only sealed，失败时仅回退为有界流式写 sealed。
     */
    private void sealPublicStaging(
            DirectUploadPartDescriptor part,
            S3Client sourceClient,
            HeadObjectResponse stagingHead,
            long deadlineNanos,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        CopyObjectRequest request = CopyObjectRequest.builder()
                .destinationBucket(part.sourceNode())
                .destinationKey(part.stagingDescriptor().sealedObjectName())
                .sourceBucket(part.sourceNode())
                .sourceKey(part.stagingObjectName())
                .copySourceIfMatch(stagingHead.eTag())
                .metadataDirective(MetadataDirective.REPLACE)
                .metadata(buildMetadata(part))
                .overrideConfiguration(requestOverrideConfiguration(
                        deadlineNanos,
                        "sealed server-side copy",
                        part.partIndex()))
                .build();
        try {
            operationIntentStore.verify(intent);
            sourceClient.copyObject(request);
            operationIntentStore.verify(intent);
            incrementTransferMetric("sealed_copy", "success");
        } catch (RuntimeException e) {
            if (isPreconditionFailure(e)) {
                incrementTransferMetric("sealed_copy", "precondition_failure");
                throw e;
            }
            incrementTransferMetric("sealed_copy", "fallback");
            streamPublicStagingToSealed(part, sourceClient, stagingHead, deadlineNanos, intent);
            incrementTransferMetric("sealed_stream", "success");
        }
    }

    /**
     * 使用可重开条件 GET 将 public staging 有界转发到同节点 sealed key。
     */
    private void streamPublicStagingToSealed(
            DirectUploadPartDescriptor part,
            S3Client sourceClient,
            HeadObjectResponse stagingHead,
            long deadlineNanos,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        int bufferSize = storageProperties.getDirectUpload().getEffectiveStreamBufferBytes();
        ContentStreamProvider provider = ContentStreamProvider.fromInputStreamSupplier(
                () -> {
                    operationIntentStore.verify(intent);
                    GetObjectRequest sourceRequest = GetObjectRequest.builder()
                            .bucket(part.sourceNode())
                            .key(part.stagingObjectName())
                            .ifMatch(stagingHead.eTag())
                            .overrideConfiguration(requestOverrideConfiguration(
                                    deadlineNanos,
                                    "public staging GET",
                                    part.partIndex()))
                            .build();
                    ResponseInputStream<GetObjectResponse> stream = sourceClient.getObject(sourceRequest);
                    try {
                        ensureVerificationWithinDeadline(deadlineNanos, part.partIndex());
                        return new DeadlineBoundInputStream(stream, bufferSize, deadlineNanos);
                    } catch (RuntimeException | Error e) {
                        closeRejectedSourceStream(stream, e);
                        throw e;
                    }
                });
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(part.sourceNode())
                .key(part.stagingDescriptor().sealedObjectName())
                .contentLength(part.size())
                .contentType("application/octet-stream")
                .metadata(buildMetadata(part))
                .overrideConfiguration(requestOverrideConfiguration(
                        deadlineNanos,
                        "sealed PUT",
                        part.partIndex()))
                .build();
        operationIntentStore.verify(intent);
        sourceClient.putObject(
                request,
                RequestBody.fromContentProvider(provider, part.size(), "application/octet-stream")
        );
        operationIntentStore.verify(intent);
        ensureVerificationWithinDeadline(deadlineNanos, part.partIndex());
    }

    /**
     * 校验 staging 的大小和调用方 ETag，并要求 provider 返回可用于条件请求的 ETag。
     */
    private void validateStagingHead(DirectUploadPartDescriptor part, HeadObjectResponse stagingHead) {
        if (stagingHead.contentLength() == null || stagingHead.contentLength() != part.size()) {
            throw new IllegalArgumentException("direct-upload staging object size mismatch");
        }
        if (stagingHead.eTag() == null || stagingHead.eTag().isBlank()) {
            throw new IllegalArgumentException("direct-upload staging object ETag is missing");
        }
        if (!normalizeEtag(stagingHead.eTag()).equals(normalizeEtag(part.eTag()))) {
            throw new IllegalArgumentException("direct-upload staging object ETag mismatch");
        }
    }

    /**
     * 以固定上限缓冲读取对象，校验 SHA-256 并返回可在 quorum 成功后提交的整文件 digest 快照。
     */
    private MessageDigest streamAndVerifyObject(
            S3Client client,
            String bucket,
            String objectName,
            HeadObjectResponse objectHead,
            DirectUploadPartDescriptor part,
            DirectUploadDigestAccumulator aggregateDigest,
            long verificationDeadline
    ) {
        MessageDigest candidateAggregateDigest = aggregateDigest.fork();
        streamAndVerifyObjectContent(
                client,
                bucket,
                objectName,
                objectHead,
                part,
                candidateAggregateDigest,
                verificationDeadline
        );
        return candidateAggregateDigest;
    }

    /**
     * 以固定上限缓冲读取 final，只校验该对象内容，不重复更新已提交的整文件 digest。
     */
    private void streamAndVerifyObjectContent(
            S3Client client,
            String bucket,
            String objectName,
            HeadObjectResponse objectHead,
            DirectUploadPartDescriptor part,
            long verificationDeadline
    ) {
        streamAndVerifyObjectContent(
                client,
                bucket,
                objectName,
                objectHead,
                part,
                null,
                verificationDeadline
        );
    }

    /**
     * 执行单对象 size/SHA-256 校验，并在需要时同时更新尚未提交的整文件 digest 快照。
     */
    private void streamAndVerifyObjectContent(
            S3Client client,
            String bucket,
            String objectName,
            HeadObjectResponse objectHead,
            DirectUploadPartDescriptor part,
            MessageDigest candidateAggregateDigest,
            long verificationDeadline
    ) {
        if (!CHECKSUM_ALGORITHM_SHA256.equalsIgnoreCase(part.checksumAlgorithm())) {
            throw new IllegalArgumentException("unsupported direct-upload checksum algorithm");
        }
        if (objectHead.contentLength() == null || objectHead.contentLength() != part.size()) {
            throw new IllegalArgumentException("direct-upload object size mismatch");
        }
        if (objectHead.eTag() == null || objectHead.eTag().isBlank()) {
            throw new IllegalArgumentException("direct-upload object ETag is missing");
        }

        MessageDigest chunkDigest = sha256Digest();
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectName)
                .ifMatch(objectHead.eTag())
                .overrideConfiguration(requestOverrideConfiguration(
                        verificationDeadline,
                        "object GET",
                        part.partIndex()))
                .build();
        int bufferSize = storageProperties.getDirectUpload().getEffectiveStreamBufferBytes();
        try (ResponseInputStream<GetObjectResponse> response = client.getObject(request);
             DeadlineBoundInputStream input = new DeadlineBoundInputStream(
                     response,
                     bufferSize,
                     verificationDeadline)) {
            byte[] buffer = new byte[bufferSize];
            long totalRead = 0;
            int read;
            while (true) {
                ensureVerificationWithinDeadline(verificationDeadline, part.partIndex());
                read = input.read(buffer);
                ensureVerificationWithinDeadline(verificationDeadline, part.partIndex());
                if (read == -1) {
                    break;
                }
                totalRead += read;
                if (totalRead > part.size()) {
                    throw new IllegalArgumentException("direct-upload object exceeded declared size");
                }
                chunkDigest.update(buffer, 0, read);
                if (candidateAggregateDigest != null) {
                    candidateAggregateDigest.update(buffer, 0, read);
                }
            }
            if (totalRead != part.size()) {
                throw new IllegalArgumentException("direct-upload object read size mismatch");
            }
        } catch (IOException e) {
            if (remainingNanos(verificationDeadline) <= 0) {
                incrementOperationMetric("verification", "timeout");
                throw new IllegalStateException(
                        "direct-upload verification timed out for part " + part.partIndex(), e);
            }
            throw new IllegalStateException("failed to stream direct-upload object", e);
        }

        String actualCipherHash = HASH_PREFIX_SHA256
                + HexFormat.of().formatHex(chunkDigest.digest());
        if (!normalizeHash(actualCipherHash).equals(normalizeHash(part.cipherHash()))) {
            throw new IllegalArgumentException("direct-upload object checksum mismatch");
        }
    }

    /**
     * 限制 staging/final 有界 hash 读取时长，使锁 lease 能覆盖完整两阶段操作。
     *
     * @param deadlineNanos 单调时钟截止点
     * @param partIndex 分片索引
     */
    private void ensureVerificationWithinDeadline(long deadlineNanos, int partIndex) {
        if (System.nanoTime() - deadlineNanos >= 0) {
            incrementOperationMetric("verification", "timeout");
            throw new IllegalStateException(
                    "direct-upload verification timed out for part " + partIndex);
        }
    }

    /**
     * 并发提升所有目标节点并在所有目标有明确终态后执行 quorum 判定和 staging 清理。
     */
    private DirectUploadPromotionResult promoteVerifiedSealed(
            DirectUploadPartDescriptor part,
            S3Client sourceClient,
            HeadObjectResponse sealedHead,
            DirectUploadOperationIntentStore.OperationIntent intent,
            TopologyLease topology
    ) {
        PromotionAttempt attempt = new PromotionAttempt(
                newOperationDeadline(),
                part.partIndex(),
                intent,
                topology
        );
        List<NodePromotionTask> tasks = new ArrayList<>(part.targetNodes().size());
        try {
            for (String targetNode : part.targetNodes()) {
                NodePromotionTask task = new NodePromotionTask(
                        part,
                        sourceClient,
                        sealedHead,
                        targetNode,
                        attempt
                );
                tasks.add(task);
                uploadExecutor.execute(task);
            }
        } catch (RejectedExecutionException e) {
            stopAndDrainPromotionTasks(tasks, attempt);
            throw new IllegalStateException("direct-upload promotion executor is saturated", e);
        }

        awaitPromotionTerminalStates(tasks, part, attempt);

        List<NodePromotion> successes = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (NodePromotionTask task : tasks) {
            try {
                successes.add(task.result().join());
            } catch (CompletionException | CancellationException e) {
                String failedNode = task.targetNode();
                failures.add(failedNode);
                Throwable failure = unwrapCompletionFailure(e);
                log.warn("直传 final 副本提升失败: targetNode={}, partIndex={}, reason={}",
                        failedNode, part.partIndex(), failure.getMessage());
                log.debug("直传 final 副本提升失败堆栈: targetNode={}, partIndex={}",
                        failedNode, part.partIndex(), failure);
            }
        }

        topology.runIfCurrent(() -> {
            validateIndependentReplicaTargets(
                    successes.stream().map(NodePromotion::nodeName).toList(),
                    part.requiredQuorum(),
                    "successful final replicas",
                    topology
            );
            if (successes.size() < part.requiredQuorum()) {
                incrementOperationMetric("complete", "quorum_failure");
                throw new IllegalStateException("direct-upload final replica quorum not reached");
            }
            operationIntentStore.verify(intent);
            persistPromotionReceipt(part, successes, intent);
        });

        NodePromotion repairSource = successes.getFirst();
        operationIntentStore.verify(intent);
        trackDegradedWrite(part, successes);
        operationIntentStore.verify(intent);
        scheduleRepairs(part, repairSource.nodeName(), failures);
        operationIntentStore.verify(intent);
        deleteStagingAfterSuccess(part, sourceClient, intent);
        incrementOperationMetric("complete", failures.isEmpty() ? "success" : "partial_success");
        return new DirectUploadPromotionResult(
                repairSource.head().contentLength(),
                repairSource.head().eTag()
        );
    }

    /**
     * 等待所有提升任务真实结束；超时会保留 staging，不使用未完成任务推断 quorum。
     */
    private void awaitPromotionTerminalStates(
            List<NodePromotionTask> tasks,
            DirectUploadPartDescriptor part,
            PromotionAttempt attempt
    ) {
        CompletableFuture<Void> all = CompletableFuture.allOf(
                tasks.stream().map(NodePromotionTask::result).toArray(CompletableFuture[]::new));
        try {
            long remainingNanos = remainingNanos(attempt.deadlineNanos());
            if (remainingNanos <= 0) {
                throw new TimeoutException("promotion deadline elapsed");
            }
            all.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (ExecutionException ignored) {
            // allOf 只会在全部子任务结束后以异常完成，随后逐项收集真实成功/失败状态。
        } catch (TimeoutException e) {
            stopAndDrainPromotionTasks(tasks, attempt);
            incrementOperationMetric("complete", "timeout");
            throw new IllegalStateException(
                    "direct-upload promotion timed out for part " + part.partIndex(), e);
        } catch (InterruptedException e) {
            stopAndDrainPromotionTasks(tasks, attempt);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("direct-upload promotion interrupted", e);
        }
    }

    /**
     * 停止本次提升、取消尚未启动的任务，并等待已经运行的 SDK 请求真实退出。
     *
     * @param tasks 本次分片的所有节点任务
     * @param attempt 共享提升截止状态
     */
    private void stopAndDrainPromotionTasks(
            List<NodePromotionTask> tasks,
            PromotionAttempt attempt
    ) {
        attempt.stop();
        tasks.forEach(NodePromotionTask::cancelBeforeStart);
        CompletableFuture.allOf(
                tasks.stream().map(NodePromotionTask::terminal).toArray(CompletableFuture[]::new)
        ).join();
    }

    /**
     * 向一个目标节点执行条件式 server-side copy 或有界流式转发并复核 final metadata。
     */
    private NodePromotion promoteToNode(
            DirectUploadPartDescriptor part,
            S3Client sourceClient,
            HeadObjectResponse sealedHead,
            String targetNode,
            PromotionAttempt attempt
    ) {
        attempt.ensureActive("target preparation");
        if (!s3Monitor.isNodeOnline(targetNode)) {
            throw new IllegalStateException("direct-upload target node is offline: " + targetNode);
        }
        S3Client targetClient = requireClient(targetNode, attempt.topology());
        ensureBucketExists(targetClient, targetNode, attempt);

        if (hasSameEndpoint(part.sourceNode(), targetNode, attempt.topology())) {
            try {
                copyWithinEndpoint(part, targetClient, sealedHead, targetNode, attempt);
                incrementTransferMetric("server_copy", "success");
            } catch (RuntimeException e) {
                if (isPreconditionFailure(e)) {
                    incrementTransferMetric("server_copy", "precondition_failure");
                    throw e;
                }
                attempt.ensureActive("server-side copy fallback");
                incrementTransferMetric("server_copy", "fallback");
                log.warn("同端点对象复制不可用，回退为有界流式转发: sourceNode={}, targetNode={}, partIndex={}",
                        part.sourceNode(), targetNode, part.partIndex(), e);
                streamAcrossEndpoints(part, sourceClient, targetClient, sealedHead, targetNode, attempt);
                incrementTransferMetric("bounded_stream", "success");
            }
        } else {
            streamAcrossEndpoints(part, sourceClient, targetClient, sealedHead, targetNode, attempt);
            incrementTransferMetric("bounded_stream", "success");
        }

        attempt.ensureActive("final HEAD");
        HeadObjectResponse finalHead = targetClient.headObject(HeadObjectRequest.builder()
                .bucket(targetNode)
                .key(part.finalObjectName())
                .overrideConfiguration(requestOverrideConfiguration(
                        attempt.deadlineNanos(),
                        "final HEAD",
                        part.partIndex()))
                .build());
        attempt.ensureActive("final HEAD");
        validateFinalObject(part, finalHead);
        streamAndVerifyObjectContent(
                targetClient,
                targetNode,
                part.finalObjectName(),
                finalHead,
                part,
                attempt.deadlineNanos()
        );
        attempt.ensureAuthorized("final content verification");
        return new NodePromotion(targetNode, finalHead);
    }

    /**
     * 使用 provider 原生 CopyObject，并用 sealed ETag 条件防止已验证快照被覆盖。
     */
    private void copyWithinEndpoint(
            DirectUploadPartDescriptor part,
            S3Client targetClient,
            HeadObjectResponse sealedHead,
            String targetNode,
            PromotionAttempt attempt
    ) {
        attempt.ensureAuthorized("server-side copy");
        CopyObjectRequest request = CopyObjectRequest.builder()
                .destinationBucket(targetNode)
                .destinationKey(part.finalObjectName())
                .sourceBucket(part.sourceNode())
                .sourceKey(part.stagingDescriptor().sealedObjectName())
                .copySourceIfMatch(sealedHead.eTag())
                .metadataDirective(MetadataDirective.REPLACE)
                .metadata(buildMetadata(part))
                .overrideConfiguration(requestOverrideConfiguration(
                        attempt.deadlineNanos(),
                        "server-side copy",
                        part.partIndex()))
                .build();
        attempt.ensureAuthorized("server-side copy");
        targetClient.copyObject(request);
        attempt.ensureAuthorized("server-side copy");
    }

    /**
     * 使用已知长度且可重开源 GET 的 request body 跨 endpoint 转发，不聚合完整对象。
     */
    private void streamAcrossEndpoints(
            DirectUploadPartDescriptor part,
            S3Client sourceClient,
            S3Client targetClient,
            HeadObjectResponse sealedHead,
            String targetNode,
            PromotionAttempt attempt
    ) {
        int bufferSize = storageProperties.getDirectUpload().getEffectiveStreamBufferBytes();
        ContentStreamProvider provider = ContentStreamProvider.fromInputStreamSupplier(
                () -> {
                    attempt.ensureAuthorized("source stream GET");
                    GetObjectRequest sourceRequest = GetObjectRequest.builder()
                            .bucket(part.sourceNode())
                            .key(part.stagingDescriptor().sealedObjectName())
                            .ifMatch(sealedHead.eTag())
                            .overrideConfiguration(requestOverrideConfiguration(
                                    attempt.deadlineNanos(),
                                    "source stream GET",
                                    part.partIndex()))
                            .build();
                    ResponseInputStream<GetObjectResponse> stream = sourceClient.getObject(sourceRequest);
                    try {
                        attempt.ensureAuthorized("source stream GET");
                        return new DeadlineBoundInputStream(
                                stream,
                                bufferSize,
                                attempt.deadlineNanos()
                        );
                    } catch (RuntimeException | Error e) {
                        closeRejectedSourceStream(stream, e);
                        throw e;
                    }
                });
        RequestBody body = RequestBody.fromContentProvider(
                provider,
                part.size(),
                "application/octet-stream"
        );
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(targetNode)
                .key(part.finalObjectName())
                .contentLength(part.size())
                .contentType("application/octet-stream")
                .metadata(buildMetadata(part))
                .overrideConfiguration(requestOverrideConfiguration(
                        attempt.deadlineNanos(),
                        "target PUT",
                        part.partIndex()))
                .build();
        attempt.ensureAuthorized("target PUT");
        targetClient.putObject(request, body);
        attempt.ensureAuthorized("target PUT");
    }

    /**
     * GET 返回响应后若共享提升尝试已经截止，立即 abort 并关闭尚未交给 SDK PUT 的响应流。
     *
     * @param stream 已建立但尚未被 request body 接管的响应流
     * @param failure 导致拒绝该响应流的原始失败
     */
    private void closeRejectedSourceStream(
            ResponseInputStream<GetObjectResponse> stream,
            Throwable failure
    ) {
        try {
            stream.abort();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        try {
            stream.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * staging 已不存在时，只在可信 final 副本达到 quorum 后按幂等 retry 返回成功。
     */
    private DirectUploadPromotionResult completeFromFinalReplicas(
            DirectUploadPartDescriptor part,
            DirectUploadDigestAccumulator aggregateDigest,
            Optional<DirectUploadPromotionReceiptStore.PromotionReceipt> receipt,
            boolean sourceAbsenceConfirmed,
            boolean authoritativeReceipt,
            DirectUploadOperationIntentStore.OperationIntent intent,
            TopologyLease topology
    ) {
        DirectUploadPromotionReceiptStore.PromotionReceipt authoritativeReceiptValue = receipt
                .orElseThrow(() -> new IllegalStateException(
                        "direct-upload promotion receipt is required when trustworthy staging is unavailable"));
        long retryDeadline = newOperationDeadline();
        int retryQuorum = authoritativeReceiptValue.requiredQuorum();
        List<RetryCandidate> candidates = resolveRetryCandidates(
                part,
                authoritativeReceiptValue,
                topology
        );
        RetryVerification verification = verifyRetryCandidates(
                part,
                aggregateDigest,
                retryDeadline,
                candidates,
                retryQuorum
        );
        topology.runIfCurrent(() -> {
            validateIndependentReplicaTargets(
                    verification.verifiedReplicas().stream().map(NodePromotion::nodeName).toList(),
                    retryQuorum,
                    "verified receipt retry replicas",
                    topology
            );
            if (verification.verifiedReplicas().size() < retryQuorum) {
                incrementOperationMetric("retry", "quorum_failure");
                throw new IllegalStateException(
                        "direct-upload staging is missing and final replica quorum is unavailable");
            }
            operationIntentStore.verify(intent);
            persistRetryPromotionReceipt(
                    part,
                    authoritativeReceiptValue,
                    verification.verifiedReplicas(),
                    intent
            );
        });
        NodePromotion source = verification.verifiedReplicas().getFirst();
        operationIntentStore.verify(intent);
        trackDegradedWrite(part, verification.verifiedReplicas());
        operationIntentStore.verify(intent);
        scheduleRepairs(part, source.nodeName(), verification.repairTargetNodes());
        operationIntentStore.verify(intent);
        if (authoritativeReceipt) {
            cleanupStagingAfterAuthoritativeReceipt(part, intent, topology);
        } else if (sourceAbsenceConfirmed) {
            retainStagingTombstone(part.stagingDescriptor(), null, intent);
            incrementOperationMetric("staging_cleanup", "success");
        } else {
            incrementOperationMetric("staging_cleanup", "retained_unknown_source");
            log.info("直传 final quorum 已确认，但 staging 源状态未知，保留生命周期记录: partIndex={}",
                    part.partIndex());
        }
        incrementOperationMetric("retry", "success");
        return new DirectUploadPromotionResult(
                source.head().contentLength(),
                source.head().eTag()
        );
    }

    /**
     * receipt 已成为终态权威后，不读取后来重建的 staging，只在 final quorum 复核成功后清理。
     * provider 暂不可用时允许生命周期任务延迟删除，但 tombstone 持久化失败必须使 complete 失败。
     */
    private void cleanupStagingAfterAuthoritativeReceipt(
            DirectUploadPartDescriptor part,
            DirectUploadOperationIntentStore.OperationIntent intent,
            TopologyLease topology
    ) {
        S3Client sourceClient = null;
        RuntimeException providerFailure = null;
        try {
            sourceClient = topology.getClient(part.sourceNode());
        } catch (RuntimeException e) {
            providerFailure = e;
        }
        if (sourceClient == null && providerFailure == null) {
            providerFailure = new IllegalStateException(
                    "storage client unavailable: " + part.sourceNode());
        }
        cleanupStagingObjectsAndRetain(
                part.stagingDescriptor(),
                sourceClient,
                providerFailure,
                intent
        );
    }

    /**
     * 按 receipt 成功节点、首次目标快照和当前目标的顺序构建去重 retry 候选集合。
     */
    private List<RetryCandidate> resolveRetryCandidates(
            DirectUploadPartDescriptor part,
            DirectUploadPromotionReceiptStore.PromotionReceipt receipt,
            TopologyLease topology
    ) {
        LinkedHashSet<String> candidateNames = new LinkedHashSet<>();
        candidateNames.addAll(receipt.successfulNodes());
        candidateNames.addAll(receipt.initialTargetNodes());
        candidateNames.addAll(part.targetNodes());

        List<RetryCandidate> candidates = new ArrayList<>();
        boolean requireIndependentPhysicalIdentity = receipt.requiredQuorum() >= 2;
        Map<String, String> physicalIdentityOwners = new LinkedHashMap<>();
        for (String nodeName : candidateNames) {
            NodeConfig nodeConfig;
            S3Client client;
            try {
                nodeConfig = topology.getNodeConfig(nodeName);
                if (nodeConfig == null) {
                    log.info("跳过已从当前配置移除的直传 retry 历史节点: node={}, partIndex={}",
                            nodeName, part.partIndex());
                    continue;
                }
                String physicalStorageId = requireIndependentPhysicalIdentity
                        ? requirePhysicalStorageId(
                                nodeConfig,
                                "promotion receipt retry topology"
                        )
                        : "";
                client = topology.getClient(nodeName);
                if (client == null) {
                    log.info("跳过客户端不可用的直传 retry 节点: node={}, partIndex={}",
                            nodeName, part.partIndex());
                    continue;
                }
                if (requireIndependentPhysicalIdentity) {
                    String existingNode = physicalIdentityOwners.putIfAbsent(
                            physicalStorageId,
                            nodeName
                    );
                    if (existingNode != null) {
                        log.warn("直传 retry 节点 {} 与 {} 使用相同物理存储身份，后者不计入仲裁: physicalStorageId={}",
                                existingNode, nodeName, physicalStorageId);
                        continue;
                    }
                }
                candidates.add(new RetryCandidate(nodeName, physicalStorageId, client));
            } catch (RuntimeException e) {
                log.warn("跳过当前无法解析的直传 retry 候选节点: node={}, partIndex={}",
                        nodeName, part.partIndex(), e);
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * 依次对 retry 候选执行 HEAD、metadata 和有界 SHA-256 校验，达到可信 quorum 后一次性提交 digest。
     *
     * @param part 分片可信描述
     * @param aggregateDigest 整文件摘要累加器
     * @param retryDeadline retry 阶段共享截止点
     * @param candidates 按显式物理存储身份有序去重的可用 final 候选
     * @param requiredQuorum receipt 固化的原 quorum
     * @return 达到 quorum 的可信副本和保守修复目标
     */
    private RetryVerification verifyRetryCandidates(
            DirectUploadPartDescriptor part,
            DirectUploadDigestAccumulator aggregateDigest,
            long retryDeadline,
            List<RetryCandidate> candidates,
            int requiredQuorum
    ) {
        List<NodePromotion> verifiedReplicas = new ArrayList<>();
        LinkedHashSet<String> repairTargetNodes = new LinkedHashSet<>();
        MessageDigest verifiedAggregate = null;
        for (RetryCandidate candidate : candidates) {
            String nodeName = candidate.nodeName();
            S3Client candidateClient = candidate.client();
            try {
                HeadObjectResponse finalHead = candidateClient.headObject(HeadObjectRequest.builder()
                        .bucket(nodeName)
                        .key(part.finalObjectName())
                        .overrideConfiguration(requestOverrideConfiguration(
                                retryDeadline,
                                "retry final HEAD",
                                part.partIndex()))
                        .build());
                validateFinalObject(part, finalHead);
                MessageDigest candidateAggregate = streamAndVerifyObject(
                        candidateClient,
                        nodeName,
                        part.finalObjectName(),
                        finalHead,
                        part,
                        aggregateDigest,
                        retryDeadline
                );
                verifiedReplicas.add(new NodePromotion(nodeName, finalHead));
                if (verifiedAggregate == null) {
                    verifiedAggregate = candidateAggregate;
                }
                if (verifiedReplicas.size() >= requiredQuorum) {
                    break;
                }
            } catch (RuntimeException e) {
                if (part.targetNodes().contains(nodeName)) {
                    repairTargetNodes.add(nodeName);
                }
                log.warn("直传 retry 候选副本内容校验失败: node={}, partIndex={}",
                        nodeName, part.partIndex(), e);
            }
        }

        if (verifiedReplicas.size() < requiredQuorum || verifiedAggregate == null) {
            incrementOperationMetric("retry", "quorum_failure");
            throw new IllegalStateException(
                    "direct-upload staging is missing and final replica quorum is unavailable");
        }

        aggregateDigest.commit(verifiedAggregate);
        Set<String> verifiedNodes = verifiedReplicas.stream()
                .map(NodePromotion::nodeName)
                .collect(java.util.stream.Collectors.toSet());
        for (String currentTarget : part.targetNodes()) {
            if (!verifiedNodes.contains(currentTarget)) {
                repairTargetNodes.add(currentTarget);
            }
        }
        return new RetryVerification(
                List.copyOf(verifiedReplicas),
                List.copyOf(repairTargetNodes)
        );
    }

    /**
     * 复核 final 对象大小和由 storage 写入的可信 metadata。
     */
    private void validateFinalObject(
            DirectUploadPartDescriptor part,
            HeadObjectResponse finalHead
    ) {
        if (finalHead.contentLength() == null || finalHead.contentLength() != part.size()) {
            throw new IllegalArgumentException("direct-upload final object size mismatch");
        }
        if (!isSafeProviderEtag(finalHead.eTag())) {
            throw new IllegalArgumentException("direct-upload final object ETag is unsafe");
        }
        Map<String, String> metadata = finalHead.metadata() != null
                ? finalHead.metadata()
                : Map.of();
        if (!normalizeHash(part.cipherHash()).equals(normalizeHash(metadata.get(METADATA_FILE_HASH)))
                || !normalizeHash(part.cipherHash()).equals(normalizeHash(metadata.get(METADATA_CIPHER_HASH)))
                || !normalizeHash(part.plainHash()).equals(normalizeHash(metadata.get(METADATA_PLAIN_HASH)))
                || !part.checksumAlgorithm().equalsIgnoreCase(
                        normalizeChecksumAlgorithm(metadata.get(METADATA_CHECKSUM_ALGORITHM)))
                || !Objects.equals(String.valueOf(part.tenantId()), metadata.get(METADATA_TENANT_ID))) {
            throw new IllegalArgumentException("direct-upload final object metadata mismatch");
        }
    }

    /**
     * 校验 final HEAD 中的 provider ETag 可安全用于条件请求和 manifest 持久化。
     *
     * @param eTag provider 返回的 final ETag
     * @return 长度不超过 manifest 列上限且全部为可见 ASCII 时返回 true
     */
    private boolean isSafeProviderEtag(String eTag) {
        if (eTag == null || eTag.length() < 1 || eTag.length() > MAX_DIRECT_UPLOAD_ETAG_LENGTH) {
            return false;
        }
        for (int index = 0; index < eTag.length(); index++) {
            char value = eTag.charAt(index);
            if (value < 0x21 || value > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构建 final 对象的租户、明文/密文 hash 和校验算法 metadata。
     */
    private Map<String, String> buildMetadata(DirectUploadPartDescriptor part) {
        return Map.of(
                METADATA_FILE_HASH, normalizeHash(part.cipherHash()),
                METADATA_TENANT_ID, String.valueOf(part.tenantId()),
                METADATA_CHECKSUM_ALGORITHM, CHECKSUM_ALGORITHM_SHA256,
                METADATA_PLAIN_HASH, normalizeHash(part.plainHash()),
                METADATA_CIPHER_HASH, normalizeHash(part.cipherHash())
        );
    }

    /**
     * 在任何 repair 或 staging cleanup 之前持久化成功证据；失败时立即终止并保留 staging 生命周期记录。
     */
    private void persistPromotionReceipt(
            DirectUploadPartDescriptor part,
            List<NodePromotion> successes,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        try {
            receiptStore.recordSuccess(
                    part,
                    successes.stream().map(NodePromotion::nodeName).toList(),
                    intent
            );
            incrementOperationMetric("receipt", "success");
        } catch (RuntimeException e) {
            incrementOperationMetric("receipt", "failure");
            log.warn("直传 final 已满足 quorum，但提升证据持久化失败并保留 staging: partIndex={}",
                    part.partIndex(), e);
            throw e;
        }
    }

    /**
     * 使用已完成 quorum 校验的权威 receipt 快照更新 retry 成功节点，禁止 TTL 竞态降低原 quorum。
     */
    private void persistRetryPromotionReceipt(
            DirectUploadPartDescriptor part,
            DirectUploadPromotionReceiptStore.PromotionReceipt authoritativeReceipt,
            List<NodePromotion> successes,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        try {
            receiptStore.recordRetrySuccess(
                    part,
                    authoritativeReceipt,
                    successes.stream().map(NodePromotion::nodeName).toList(),
                    intent
            );
            incrementOperationMetric("receipt", "success");
        } catch (RuntimeException e) {
            incrementOperationMetric("receipt", "failure");
            log.warn("直传 final retry 的权威 receipt 已过期、漂移或失去 fence: partIndex={}",
                    part.partIndex(), e);
            throw e;
        }
    }

    /**
     * 对所有失败目标从一个已验证 final 副本调度现有一致性修复流程。
     */
    private void scheduleRepairs(
            DirectUploadPartDescriptor part,
            String sourceNode,
            List<String> failedNodes
    ) {
        for (String failedNode : new LinkedHashSet<>(failedNodes)) {
            if (sourceNode.equals(failedNode)) {
                continue;
            }
            try {
                consistencyRepairService.scheduleImmediateRepairByNodesAsync(
                        part.finalObjectName(),
                        sourceNode,
                        failedNode
                ).whenComplete((repaired, error) -> {
                    if (error == null && Boolean.TRUE.equals(repaired)) {
                        degradedWriteTracker.markNodeRepaired(
                                part.cipherHash(),
                                part.tenantId(),
                                failedNode
                        );
                        incrementOperationMetric("repair", "success");
                    } else {
                        incrementOperationMetric("repair", "failure");
                        log.warn("直传 final 副本修复未完成，保留 degraded 记录: sourceNode={}, targetNode={}, partIndex={}",
                                sourceNode, failedNode, part.partIndex(), error);
                    }
                });
                incrementOperationMetric("repair_schedule", "accepted");
            } catch (RuntimeException e) {
                incrementOperationMetric("repair_schedule", "failure");
                log.error("调度直传 final 副本修复失败: sourceNode={}, targetNode={}",
                        sourceNode, failedNode, e);
            }
        }
    }

    /**
     * 将已验证副本交给 tracker 按当前精确 hash placement 判断并持久化缺口。
     *
     * <p>即使成功数达到副本因子也不能跳过：拓扑迁移或同域 fallback 可能使这些成功节点
     * 不再是当前 planned target，而 tracker 会在 placement 完整时自行无写入返回。</p>
     */
    private void trackDegradedWrite(
            DirectUploadPartDescriptor part,
            List<NodePromotion> successes
    ) {
        StorageProperties.DegradedWriteConfig config = storageProperties.getDegradedWrite();
        Set<String> successfulNodes = successes.stream()
                .map(NodePromotion::nodeName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean replicaDeficit = successes.size() < storageProperties.getEffectiveReplicationFactor()
                || part.targetNodes().size() < storageProperties.getEffectiveReplicationFactor()
                || !successfulNodes.containsAll(part.targetNodes());
        if (replicaDeficit
                && (config == null || !config.isEnabled() || !config.isTrackForSync())) {
            throw new IllegalStateException(
                    "direct-upload degraded replica evidence tracking is unavailable");
        }
        storageProperties.validateDegradedWriteTracking();
        if (config == null || !config.isTrackForSync()) {
            return;
        }
        degradedWriteTracker.recordAuthoritativeDegradedWrite(
                part.cipherHash(),
                successes.stream().map(NodePromotion::nodeName).toList(),
                part.tenantId()
        );
    }

    /**
     * quorum 成功且 final 修复源存在后删除 public/sealed，并持久化重放防护 tombstone。
     */
    private void deleteStagingAfterSuccess(
            DirectUploadPartDescriptor part,
            S3Client sourceClient,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        cleanupStagingObjectsAndRetain(part.stagingDescriptor(), sourceClient, null, intent);
    }

    /**
     * 独立尝试删除 public 与 sealed；provider 失败可交给生命周期任务重试，tombstone 失败则失败关闭。
     *
     * @param descriptor staging 生命周期身份
     * @param sourceClient 可用的源 provider 客户端；不可用时传 {@code null}
     * @param initialProviderFailure 客户端解析阶段的 provider 失败
     */
    private void cleanupStagingObjectsAndRetain(
            DirectUploadStagingDescriptor descriptor,
            S3Client sourceClient,
            RuntimeException initialProviderFailure,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        RuntimeException providerFailure = initialProviderFailure;
        if (sourceClient != null) {
            long cleanupDeadline = newCleanupDeadline();
            providerFailure = deleteTrackedObjectOrCapture(
                    sourceClient,
                    descriptor,
                    descriptor.objectName(),
                    cleanupDeadline,
                    providerFailure,
                    intent
            );
            providerFailure = deleteTrackedObjectOrCapture(
                    sourceClient,
                    descriptor,
                    descriptor.sealedObjectName(),
                    cleanupDeadline,
                    providerFailure,
                    intent
            );
        }
        retainStagingTombstone(descriptor, providerFailure, intent);
        if (providerFailure == null) {
            incrementOperationMetric("staging_cleanup", "success");
            return;
        }
        incrementOperationMetric("staging_cleanup", "deferred");
        log.warn("直传对象已进入 tombstone 保留期，public/sealed 删除将由生命周期任务重试: node={}, partIndex={}",
                descriptor.nodeName(), descriptor.partIndex(), providerFailure);
    }

    /**
     * 删除一个受跟踪对象并聚合 provider 失败，确保另一个对象仍会被尝试删除。
     */
    private RuntimeException deleteTrackedObjectOrCapture(
            S3Client sourceClient,
            DirectUploadStagingDescriptor descriptor,
            String objectName,
            long cleanupDeadline,
            RuntimeException previousFailure,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        operationIntentStore.verify(intent);
        try {
            deleteStagingObject(
                    sourceClient,
                    descriptor.nodeName(),
                    objectName,
                    cleanupDeadline,
                    descriptor.partIndex()
            );
        } catch (RuntimeException e) {
            if (previousFailure == null) {
                return e;
            }
            if (previousFailure != e) {
                previousFailure.addSuppressed(e);
            }
        }
        return previousFailure;
    }

    /**
     * 持久化完整保留期 tombstone；失败时附带 provider 删除异常并向 complete/abort 传播。
     */
    private void retainStagingTombstone(
            DirectUploadStagingDescriptor descriptor,
            RuntimeException providerFailure,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        operationIntentStore.verify(intent);
        try {
            stagingTracker.retainAfterDelete(descriptor);
        } catch (RuntimeException tombstoneFailure) {
            if (providerFailure != null) {
                tombstoneFailure.addSuppressed(providerFailure);
            }
            incrementOperationMetric("staging_cleanup", "tombstone_failure");
            throw new IllegalStateException(
                    "failed to persist direct-upload staging tombstone",
                    tombstoneFailure
            );
        }
        operationIntentStore.retire(
                intent,
                Duration.ofHours(
                        storageProperties.getDirectUpload().getEffectiveStagingRetentionHours()
                )
        );
    }

    /**
     * 幂等删除 staging；bucket/object 已不存在时视为已完成，其他 provider 错误继续失败。
     *
     * @param client 源节点客户端
     * @param bucket staging bucket
     * @param objectName staging key
     */
    private void deleteStagingObject(
            S3Client client,
            String bucket,
            String objectName,
            long deadlineNanos,
            int partIndex
    ) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectName)
                    .overrideConfiguration(requestOverrideConfiguration(
                            deadlineNanos,
                            "staging DELETE",
                            partIndex))
                    .build());
        } catch (S3Exception e) {
            if (!isMissingObject(e)) {
                throw e;
            }
            log.info("直传 staging 已不存在，按幂等清理完成: node={}", bucket);
        }
    }

    /**
     * 确认目标桶存在并缓存成功结果，避免每次副本提升都重复 HEAD bucket。
     */
    private void ensureBucketExists(
            S3Client client,
            String nodeName,
            PromotionAttempt attempt
    ) {
        attempt.ensureActive("target bucket HEAD");
        String topologyBucketKey = attempt.topology().revision() + ":" + nodeName;
        if (Boolean.TRUE.equals(bucketExistenceCache.getIfPresent(topologyBucketKey))) {
            return;
        }
        try {
            client.headBucket(HeadBucketRequest.builder()
                    .bucket(nodeName)
                    .overrideConfiguration(requestOverrideConfiguration(
                            attempt.deadlineNanos(),
                            "target bucket HEAD",
                            attempt.partIndex()))
                    .build());
            attempt.ensureActive("target bucket HEAD");
        } catch (NoSuchBucketException e) {
            createBucket(client, nodeName, attempt);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                createBucket(client, nodeName, attempt);
            } else {
                throw e;
            }
        }
        bucketExistenceCache.put(topologyBucketKey, true);
    }

    /**
     * 在本次 promotion 的共享截止时间内创建目标桶。
     *
     * @param client 目标 S3 客户端
     * @param nodeName 目标桶名
     * @param attempt promotion 尝试状态
     */
    private void createBucket(
            S3Client client,
            String nodeName,
            PromotionAttempt attempt
    ) {
        attempt.ensureAuthorized("target bucket CREATE");
        client.createBucket(CreateBucketRequest.builder()
                .bucket(nodeName)
                .overrideConfiguration(requestOverrideConfiguration(
                        attempt.deadlineNanos(),
                        "target bucket CREATE",
                        attempt.partIndex()))
                .build());
        attempt.ensureAuthorized("target bucket CREATE");
    }

    /**
     * 获取指定节点客户端，缺失时失败关闭。
     */
    private S3Client requireClient(String nodeName, TopologyLease topology) {
        S3Client client = topology.getClient(nodeName);
        if (client == null) {
            throw new IllegalStateException("S3 client is unavailable for node " + nodeName);
        }
        return client;
    }

    /**
     * 比较规范化 endpoint；同节点始终视为同 endpoint。
     */
    private boolean hasSameEndpoint(
            String sourceNode,
            String targetNode,
            TopologyLease topology
    ) {
        if (sourceNode.equals(targetNode)) {
            return true;
        }
        NodeConfig source = topology.getNodeConfig(sourceNode);
        NodeConfig target = topology.getNodeConfig(targetNode);
        if (source == null || target == null) {
            return false;
        }
        String sourceEndpoint = canonicalEndpoint(source.getEndpoint());
        String targetEndpoint = canonicalEndpoint(target.getEndpoint());
        return !sourceEndpoint.isEmpty()
                && !targetEndpoint.isEmpty()
                && sourceEndpoint.equals(targetEndpoint);
    }

    /**
     * 使用显式物理存储身份拒绝同一底层集群的别名节点重复计入副本仲裁。
     *
     * <p>当需要两个及以上副本时，任何缺失、非法或重复 physical-storage-id 都无法证明
     * 副本独立，必须在对象读写前失败关闭。该校验不尝试通过 DNS 或端口推断物理拓扑。</p>
     *
     * @param targetNodes 当前拓扑或 receipt 重试候选节点
     * @param topologyLabel 仅用于错误定位的拓扑来源
     */
    private void validateIndependentReplicaTargets(
            List<String> targetNodes,
            int requiredIndependentReplicas,
            String topologyLabel,
            TopologyLease topology
    ) {
        if (targetNodes == null || targetNodes.isEmpty()) {
            return;
        }
        Set<String> uniqueNodeNames = new LinkedHashSet<>();
        Map<String, String> physicalIdentityOwners = new LinkedHashMap<>();
        for (String nodeName : targetNodes) {
            if (nodeName == null || nodeName.isBlank() || !uniqueNodeNames.add(nodeName)) {
                throw new IllegalStateException(
                        "direct-upload topology contains duplicate or invalid target node: "
                                + topologyLabel);
            }
            if (requiredIndependentReplicas < 2) {
                continue;
            }
            NodeConfig config = topology.getNodeConfig(nodeName);
            if (config == null) {
                throw new IllegalStateException(
                        "direct-upload physical target identity is unavailable: "
                                + topologyLabel);
            }
            String physicalStorageId = requirePhysicalStorageId(config, topologyLabel);
            String existingNode = physicalIdentityOwners.putIfAbsent(physicalStorageId, nodeName);
            if (existingNode != null) {
                log.error("直传拓扑中的节点 {} 与 {} 使用同一物理存储身份，拒绝计入独立副本: physicalStorageId={}, topology={}",
                        existingNode, nodeName, physicalStorageId, topologyLabel);
                throw new IllegalStateException(
                        "direct-upload topology contains duplicate physical storage identity: "
                                + topologyLabel);
            }
        }
    }

    /**
     * 校验并返回节点配置中的显式物理存储身份。
     */
    private String requirePhysicalStorageId(NodeConfig config, String topologyLabel) {
        String physicalStorageId = config == null || config.getPhysicalStorageId() == null
                ? ""
                : config.getPhysicalStorageId().trim();
        if (!PHYSICAL_STORAGE_ID_PATTERN.matcher(physicalStorageId).matches()) {
            throw new IllegalStateException(
                    "direct-upload physical storage identity is unavailable or invalid: "
                            + topologyLabel);
        }
        return physicalStorageId;
    }

    /**
     * 规范化 endpoint 的 scheme、authority、path 和尾部斜杠。
     */
    private String canonicalEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        URI uri = URI.create(endpoint.trim()).normalize();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String authority = uri.getRawAuthority() == null
                ? ""
                : uri.getRawAuthority().toLowerCase(Locale.ROOT);
        if (scheme.isEmpty() || authority.isEmpty()) {
            return "";
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/") && !path.isEmpty()) {
            path = path.substring(0, path.length() - 1);
        }
        return scheme + "://" + authority + path;
    }

    /**
     * 判断异常链是否表示源 ETag 条件失败；该错误禁止回退到无条件流式复制。
     */
    private boolean isPreconditionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof S3Exception s3Exception) {
                String code = s3Exception.awsErrorDetails() != null
                        ? s3Exception.awsErrorDetails().errorCode()
                        : "";
                if (s3Exception.statusCode() == 412
                        || "PreconditionFailed".equalsIgnoreCase(code)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 兼容不同 S3 provider 的对象不存在响应。
     */
    private boolean isMissingObject(S3Exception exception) {
        String code = exception.awsErrorDetails() != null
                ? exception.awsErrorDetails().errorCode()
                : "";
        return exception.statusCode() == 404 || "NoSuchKey".equalsIgnoreCase(code);
    }

    /**
     * 创建一轮有界对象存储操作的单调时钟截止点。
     *
     * @return 纳秒级绝对截止点
     */
    private long newOperationDeadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(
                storageProperties.getDirectUpload().getEffectiveTransferTimeoutSeconds());
    }

    /**
     * 为成功后的 staging 删除保留最多一分钟，确保锁的两阶段 lease 预算可覆盖清理。
     *
     * @return 纳秒级清理截止点
     */
    private long newCleanupDeadline() {
        int timeoutSeconds = Math.min(
                60,
                storageProperties.getDirectUpload().getEffectiveTransferTimeoutSeconds()
        );
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    }

    /**
     * 计算截止点的剩余纳秒，使用单调时钟避免系统时间跳变。
     *
     * @param deadlineNanos 绝对截止点
     * @return 剩余纳秒，可为非正数
     */
    private long remainingNanos(long deadlineNanos) {
        return deadlineNanos - System.nanoTime();
    }

    /**
     * 为每个 AWS SDK 请求绑定整次 API 调用和单次尝试超时。
     *
     * @param deadlineNanos 当前阶段共享截止点
     * @param operation 操作说明
     * @param partIndex 分片索引
     * @return 请求级超时配置
     */
    private AwsRequestOverrideConfiguration requestOverrideConfiguration(
            long deadlineNanos,
            String operation,
            int partIndex
    ) {
        long remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0) {
            throw new IllegalStateException(
                    "direct-upload " + operation + " timed out for part " + partIndex);
        }
        long attemptNanos = Math.max(
                1L,
                Math.min(TimeUnit.SECONDS.toNanos(60), remaining / 2)
        );
        return AwsRequestOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofNanos(remaining))
                .apiCallAttemptTimeout(Duration.ofNanos(attemptNanos))
                .build();
    }

    /**
     * 展开异步包装异常，日志仅记录真实 provider/校验失败原因。
     *
     * @param throwable 异步任务异常
     * @return 最内层可诊断原因
     */
    private Throwable unwrapCompletionFailure(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 创建 SHA-256 digest。
     */
    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance(CHECKSUM_ALGORITHM_SHA256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * 规范化 ETag 的引号和空白差异。
     */
    private String normalizeEtag(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() >= 2
                && normalized.charAt(0) == '\"'
                && normalized.charAt(normalized.length() - 1) == '\"') {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 规范化 checksum 算法文本。
     */
    private String normalizeChecksumAlgorithm(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 规范化 sha256 前缀 hash 文本。
     */
    private String normalizeHash(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 记录低基数的完整 promotion 操作结果。
     */
    private void incrementOperationMetric(String operation, String result) {
        meterRegistry.counter(
                "storage_direct_upload_operations_total",
                "operation",
                operation,
                "result",
                result
        ).increment();
    }

    /**
     * 记录 server copy 与 bounded stream 的低基数传输结果。
     */
    private void incrementTransferMetric(String mode, String result) {
        meterRegistry.counter(
                "storage_direct_upload_transfers_total",
                "mode",
                mode,
                "result",
                result
        ).increment();
    }

    /**
     * 单轮节点提升共享的截止状态；主线程停止后，worker 禁止继续产生 provider 副作用。
     */
    private final class PromotionAttempt {
        private final long deadlineNanos;
        private final int partIndex;
        private final DirectUploadOperationIntentStore.OperationIntent intent;
        private final TopologyLease topology;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private PromotionAttempt(
                long deadlineNanos,
                int partIndex,
                DirectUploadOperationIntentStore.OperationIntent intent,
                TopologyLease topology
        ) {
            this.deadlineNanos = deadlineNanos;
            this.partIndex = partIndex;
            this.intent = intent;
            this.topology = topology;
        }

        /**
         * 复核本轮任务仍被允许运行且未超过共享截止时间。
         *
         * @param operation 当前操作说明
         */
        private void ensureActive(String operation) {
            if (!active.get() || remainingNanos(deadlineNanos) <= 0) {
                throw new IllegalStateException(
                        "direct-upload " + operation + " timed out for part " + partIndex);
            }
        }

        /**
         * 在写入、复制、建桶等对象存储副作用前同时复核截止状态和持久化操作意图。
         *
         * @param operation 当前对象存储副作用说明
         */
        private void ensureAuthorized(String operation) {
            ensureActive(operation);
            operationIntentStore.verify(intent);
            topology.verifyCurrent();
            ensureActive(operation);
        }

        /**
         * 阻止 worker 在主线程已判定超时或中断后继续发起新请求。
         */
        private void stop() {
            active.set(false);
        }

        private long deadlineNanos() {
            return deadlineNanos;
        }

        private int partIndex() {
            return partIndex;
        }

        /**
         * 返回本轮提升固定使用的客户端与配置 topology lease。
         */
        private TopologyLease topology() {
            return topology;
        }
    }

    /**
     * 节点任务状态，用于区分可安全取消的排队任务与必须等待退出的运行任务。
     */
    private enum PromotionTaskState {
        QUEUED,
        RUNNING,
        CANCELLED,
        DONE
    }

    /**
     * 自管生命周期的节点提升任务，terminal 仅在 worker 已退出或确认未启动时完成。
     */
    private final class NodePromotionTask implements Runnable {
        private final DirectUploadPartDescriptor part;
        private final S3Client sourceClient;
        private final HeadObjectResponse sealedHead;
        private final String targetNode;
        private final PromotionAttempt attempt;
        private final AtomicReference<PromotionTaskState> state =
                new AtomicReference<>(PromotionTaskState.QUEUED);
        private final CompletableFuture<NodePromotion> result = new CompletableFuture<>();
        private final CompletableFuture<Void> terminal = new CompletableFuture<>();

        private NodePromotionTask(
                DirectUploadPartDescriptor part,
                S3Client sourceClient,
                HeadObjectResponse sealedHead,
                String targetNode,
                PromotionAttempt attempt
        ) {
            this.part = part;
            this.sourceClient = sourceClient;
            this.sealedHead = sealedHead;
            this.targetNode = targetNode;
            this.attempt = attempt;
        }

        /**
         * 仅允许一个 executor worker 从排队态进入运行态，并始终发布 terminal。
         */
        @Override
        public void run() {
            if (!state.compareAndSet(PromotionTaskState.QUEUED, PromotionTaskState.RUNNING)) {
                return;
            }
            try {
                attempt.ensureActive("promotion task");
                result.complete(promoteToNode(
                        part,
                        sourceClient,
                        sealedHead,
                        targetNode,
                        attempt
                ));
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
                if (throwable instanceof Error error) {
                    throw error;
                }
            } finally {
                state.set(PromotionTaskState.DONE);
                terminal.complete(null);
            }
        }

        /**
         * 取消尚未启动的任务；运行中的任务由 attempt 截止和 SDK 超时负责退出。
         */
        private void cancelBeforeStart() {
            if (state.compareAndSet(PromotionTaskState.QUEUED, PromotionTaskState.CANCELLED)) {
                result.completeExceptionally(new CancellationException(
                        "direct-upload promotion cancelled before start"));
                terminal.complete(null);
            }
        }

        private CompletableFuture<NodePromotion> result() {
            return result;
        }

        private CompletableFuture<Void> terminal() {
            return terminal;
        }

        private String targetNode() {
            return targetNode;
        }
    }

    /**
     * 到达共享截止时间时主动 abort 响应流，覆盖 GET 返回 headers 后的阻塞读取阶段。
     */
    private final class DeadlineBoundInputStream extends BufferedInputStream {
        private final ScheduledFuture<?> abortFuture;

        private DeadlineBoundInputStream(
                ResponseInputStream<GetObjectResponse> response,
                int bufferSize,
                long deadlineNanos
        ) {
            super(response, bufferSize);
            long delayNanos = Math.max(0L, remainingNanos(deadlineNanos));
            this.abortFuture = DEADLINE_ABORT_SCHEDULER.schedule(
                    response::abort,
                    delayNanos,
                    TimeUnit.NANOSECONDS
            );
        }

        /**
         * 正常关闭时撤销延迟 abort，并关闭底层 SDK 响应。
         */
        @Override
        public void close() throws IOException {
            abortFuture.cancel(false);
            super.close();
        }
    }

    /**
     * 一个已完成且通过 final HEAD/metadata 复核的节点结果。
     */
    private record NodePromotion(
            String nodeName,
            HeadObjectResponse head
    ) {
    }

    /**
     * retry 达到原 quorum 后返回的可信副本和当前拓扑修复目标。
     */
    private record RetryVerification(
            List<NodePromotion> verifiedReplicas,
            List<String> repairTargetNodes
    ) {
    }

    /**
     * 已解析客户端且按显式物理存储身份去重的 retry 候选。
     */
    private record RetryCandidate(
            String nodeName,
            String physicalStorageId,
            S3Client client
    ) {
    }
}
