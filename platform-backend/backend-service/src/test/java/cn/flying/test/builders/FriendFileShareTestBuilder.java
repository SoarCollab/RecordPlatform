package cn.flying.test.builders;

import cn.flying.dao.entity.FriendFileShare;

import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class FriendFileShareTestBuilder {

    private static final AtomicLong idCounter = new AtomicLong(1L);

    public static FriendFileShare aFriendFileShare() {
        return new FriendFileShare()
                .setId(idCounter.getAndIncrement())
                .setTenantId(1L)
                .setSharerId(100L)
                .setFriendId(200L)
                .setFileHashes("[\"sha256_test_hash_1\"]")
                .setMessage("Check out this file!")
                .setIsRead(0)
                .setStatus(FriendFileShare.STATUS_ACTIVE)
                .setCreateTime(new Date());
    }

    public static FriendFileShare aFriendFileShare(Consumer<FriendFileShare> customizer) {
        FriendFileShare share = aFriendFileShare();
        customizer.accept(share);
        return share;
    }

    public static FriendFileShare aShareFromTo(Long sharerId, Long friendId) {
        return aFriendFileShare(s -> {
            s.setSharerId(sharerId);
            s.setFriendId(friendId);
        });
    }

    public static FriendFileShare aShareWithFiles(String... fileHashes) {
        String json = "[" + String.join(",", 
            java.util.Arrays.stream(fileHashes)
                .map(h -> "\"" + h + "\"")
                .toArray(String[]::new)
        ) + "]";
        return aFriendFileShare(s -> s.setFileHashes(json));
    }

    public static FriendFileShare aReadShare() {
        return aFriendFileShare(s -> {
            s.setIsRead(1);
            s.setReadTime(new Date());
        });
    }

    public static FriendFileShare anUnreadShare() {
        return aFriendFileShare(s -> s.setIsRead(0));
    }

    public static FriendFileShare aCancelledShare() {
        return aFriendFileShare(s -> s.setStatus(FriendFileShare.STATUS_CANCELLED));
    }

    public static FriendFileShare aShareWithMessage(String message) {
        return aFriendFileShare(s -> s.setMessage(message));
    }

    public static void resetIdCounter() {
        idCounter.set(1L);
    }
}
