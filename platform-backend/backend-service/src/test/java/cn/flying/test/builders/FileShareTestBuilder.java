package cn.flying.test.builders;

import cn.flying.dao.dto.FileShare;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class FileShareTestBuilder {

    private static final AtomicLong idCounter = new AtomicLong(1L);

    public static FileShare aFileShare() {
        return new FileShare()
                .setId(idCounter.getAndIncrement())
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode(generateShareCode())
                .setShareType(0)
                .setFileHashes("[\"sha256_test_hash_1\"]")
                .setExpireTime(new Date(System.currentTimeMillis() + 3600000))
                .setAccessCount(0)
                .setStatus(FileShare.STATUS_ACTIVE)
                .setCreateTime(new Date())
                .setUpdateTime(new Date())
                .setDeleted(0);
    }

    public static FileShare aFileShare(Consumer<FileShare> customizer) {
        FileShare share = aFileShare();
        customizer.accept(share);
        return share;
    }

    public static FileShare aPublicShare() {
        return aFileShare(s -> s.setShareType(0));
    }

    public static FileShare aPrivateShare() {
        return aFileShare(s -> s.setShareType(1));
    }

    public static FileShare aShareForUser(Long userId) {
        return aFileShare(s -> s.setUserId(userId));
    }

    public static FileShare aShareWithCode(String shareCode) {
        return aFileShare(s -> s.setShareCode(shareCode));
    }

    public static FileShare aShareWithFiles(String... fileHashes) {
        String json = "[" + String.join(",", 
            java.util.Arrays.stream(fileHashes)
                .map(h -> "\"" + h + "\"")
                .toArray(String[]::new)
        ) + "]";
        return aFileShare(s -> s.setFileHashes(json));
    }

    public static FileShare anExpiredShare() {
        return aFileShare(s -> {
            s.setExpireTime(new Date(System.currentTimeMillis() - 3600000));
            s.setStatus(FileShare.STATUS_EXPIRED);
        });
    }

    public static FileShare aCancelledShare() {
        return aFileShare(s -> s.setStatus(FileShare.STATUS_CANCELLED));
    }

    public static FileShare aShareExpiringIn(int minutes) {
        return aFileShare(s -> s.setExpireTime(new Date(System.currentTimeMillis() + minutes * 60000L)));
    }

    public static FileShare aShareWithAccessCount(int count) {
        return aFileShare(s -> s.setAccessCount(count));
    }

    private static String generateShareCode() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    public static void resetIdCounter() {
        idCounter.set(1L);
    }
}
