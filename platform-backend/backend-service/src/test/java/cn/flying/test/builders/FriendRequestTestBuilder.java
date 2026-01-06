package cn.flying.test.builders;

import cn.flying.dao.entity.FriendRequest;

import java.util.Date;
import java.util.function.Consumer;

/**
 * Test data builder for FriendRequest entity.
 * Provides fluent API for creating test fixtures.
 */
public class FriendRequestTestBuilder {

    private static long idCounter = 1L;

    public static FriendRequest aFriendRequest() {
        return new FriendRequest()
                .setId(idCounter++)
                .setRequesterId(100L)
                .setAddresseeId(200L)
                .setMessage("Hi, let's be friends!")
                .setStatus(FriendRequest.STATUS_PENDING)
                .setCreateTime(new Date())
                .setUpdateTime(new Date());
    }

    public static FriendRequest aFriendRequest(Consumer<FriendRequest> customizer) {
        FriendRequest request = aFriendRequest();
        customizer.accept(request);
        return request;
    }

    public static FriendRequest aPendingRequest(Long requesterId, Long addresseeId) {
        return aFriendRequest(r -> r
                .setRequesterId(requesterId)
                .setAddresseeId(addresseeId)
                .setStatus(FriendRequest.STATUS_PENDING));
    }

    public static FriendRequest anAcceptedRequest(Long requesterId, Long addresseeId) {
        return aFriendRequest(r -> r
                .setRequesterId(requesterId)
                .setAddresseeId(addresseeId)
                .setStatus(FriendRequest.STATUS_ACCEPTED));
    }

    public static FriendRequest aRejectedRequest(Long requesterId, Long addresseeId) {
        return aFriendRequest(r -> r
                .setRequesterId(requesterId)
                .setAddresseeId(addresseeId)
                .setStatus(FriendRequest.STATUS_REJECTED));
    }

    public static void resetIdCounter() {
        idCounter = 1L;
    }
}
