package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.FriendRequest;
import cn.flying.dao.entity.Friendship;
import cn.flying.dao.mapper.FriendRequestMapper;
import cn.flying.dao.mapper.FriendshipMapper;
import cn.flying.dao.vo.friend.FriendRequestDetailVO;
import cn.flying.dao.vo.friend.FriendVO;
import cn.flying.dao.vo.friend.SendFriendRequestVO;
import cn.flying.dao.vo.friend.UserSearchVO;
import cn.flying.service.AccountService;
import cn.flying.service.FriendService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FriendServiceImpl extends ServiceImpl<FriendshipMapper, Friendship>
        implements FriendService {

    @Resource
    private FriendRequestMapper friendRequestMapper;

    @Resource
    private AccountService accountService;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Override
    public boolean areFriends(Long userA, Long userB) {
        if (userA == null || userB == null || userA.equals(userB)) {
            return false;
        }
        Long ua = Math.min(userA, userB);
        Long ub = Math.max(userA, userB);
        return baseMapper.areFriends(ua, ub, TenantContext.getTenantId()) > 0;
    }

    @Override
    @Transactional
    public FriendRequest sendRequest(Long requesterId, SendFriendRequestVO vo) {
        Long addresseeId = IdUtils.fromExternalId(vo.getAddresseeId());

        // 不能添加自己
        if (requesterId.equals(addresseeId)) {
            throw new GeneralException(ResultEnum.CANNOT_ADD_SELF);
        }

        // 检查目标用户是否存在
        Account addressee = accountService.findAccountById(addresseeId);
        if (addressee == null) {
            throw new GeneralException(ResultEnum.USER_NOT_EXIST);
        }

        // 检查是否已经是好友
        if (areFriends(requesterId, addresseeId)) {
            throw new GeneralException(ResultEnum.ALREADY_FRIENDS);
        }

        // 检查是否已有待处理的请求
        Long tenantId = TenantContext.getTenantId();
        if (friendRequestMapper.countPendingBetween(requesterId, addresseeId, tenantId) > 0) {
            throw new GeneralException(ResultEnum.FRIEND_REQUEST_EXISTS);
        }

        // 创建好友请求
        FriendRequest request = new FriendRequest()
                .setRequesterId(requesterId)
                .setAddresseeId(addresseeId)
                .setMessage(vo.getMessage())
                .setStatus(FriendRequest.STATUS_PENDING);

        friendRequestMapper.insert(request);
        log.info("发送好友请求: id={}, requester={}, addressee={}", request.getId(), requesterId, addresseeId);

        // SSE 通知接收者
        Account requester = accountService.findAccountById(requesterId);
        sseEmitterManager.sendToUser(tenantId, addresseeId, SseEvent.of(SseEventType.FRIEND_REQUEST, Map.of(
                "requestId", IdUtils.toExternalId(request.getId()),
                "requesterName", requester != null ? requester.getUsername() : "未知用户",
                "message", vo.getMessage() != null ? vo.getMessage() : ""
        )));

        return request;
    }

    @Override
    @Transactional
    public void acceptRequest(Long userId, String requestId) {
        Long reqId = IdUtils.fromExternalId(requestId);
        FriendRequest request = friendRequestMapper.selectById(reqId);

        if (request == null) {
            throw new GeneralException(ResultEnum.FRIEND_REQUEST_NOT_FOUND);
        }

        if (!request.getAddresseeId().equals(userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }

        if (request.isProcessed()) {
            throw new GeneralException(ResultEnum.FRIEND_REQUEST_PROCESSED);
        }

        // 更新请求状态
        request.setStatus(FriendRequest.STATUS_ACCEPTED);
        friendRequestMapper.updateById(request);

        // 创建好友关系
        Friendship friendship = Friendship.create(request.getRequesterId(), userId, request.getId());
        this.save(friendship);

        log.info("接受好友请求: requestId={}, friendship={}", reqId, friendship.getId());

        // SSE 通知请求者
        Long tenantId = TenantContext.getTenantId();
        Account accepter = accountService.findAccountById(userId);
        sseEmitterManager.sendToUser(tenantId, request.getRequesterId(), SseEvent.of(SseEventType.FRIEND_ACCEPTED, Map.of(
                "friendId", IdUtils.toExternalId(userId),
                "friendName", accepter != null ? accepter.getUsername() : "未知用户"
        )));
    }

    @Override
    @Transactional
    public void rejectRequest(Long userId, String requestId) {
        Long reqId = IdUtils.fromExternalId(requestId);
        FriendRequest request = friendRequestMapper.selectById(reqId);

        if (request == null) {
            throw new GeneralException(ResultEnum.FRIEND_REQUEST_NOT_FOUND);
        }

        if (!request.getAddresseeId().equals(userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }

        if (request.isProcessed()) {
            throw new GeneralException(ResultEnum.FRIEND_REQUEST_PROCESSED);
        }

        request.setStatus(FriendRequest.STATUS_REJECTED);
        friendRequestMapper.updateById(request);
        log.info("拒绝好友请求: requestId={}", reqId);
    }

    @Override
    @Transactional
    public void cancelRequest(Long userId, String requestId) {
        Long reqId = IdUtils.fromExternalId(requestId);
        FriendRequest request = friendRequestMapper.selectById(reqId);

        if (request == null) {
            throw new GeneralException(ResultEnum.FRIEND_REQUEST_NOT_FOUND);
        }

        if (!request.getRequesterId().equals(userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }

        if (request.isProcessed()) {
            throw new GeneralException(ResultEnum.FRIEND_REQUEST_PROCESSED);
        }

        request.setStatus(FriendRequest.STATUS_CANCELLED);
        friendRequestMapper.updateById(request);
        log.info("取消好友请求: requestId={}", reqId);
    }

    @Override
    public IPage<FriendRequestDetailVO> getReceivedRequests(Long userId, Page<?> page) {
        LambdaQueryWrapper<FriendRequest> wrapper = new LambdaQueryWrapper<FriendRequest>()
                .eq(FriendRequest::getAddresseeId, userId)
                .orderByDesc(FriendRequest::getCreateTime);

        Page<FriendRequest> requestPage = new Page<>(page.getCurrent(), page.getSize());
        IPage<FriendRequest> result = friendRequestMapper.selectPage(requestPage, wrapper);

        return result.convert(request -> convertRequestToVO(request, true));
    }

    @Override
    public IPage<FriendRequestDetailVO> getSentRequests(Long userId, Page<?> page) {
        LambdaQueryWrapper<FriendRequest> wrapper = new LambdaQueryWrapper<FriendRequest>()
                .eq(FriendRequest::getRequesterId, userId)
                .orderByDesc(FriendRequest::getCreateTime);

        Page<FriendRequest> requestPage = new Page<>(page.getCurrent(), page.getSize());
        IPage<FriendRequest> result = friendRequestMapper.selectPage(requestPage, wrapper);

        return result.convert(request -> convertRequestToVO(request, false));
    }

    @Override
    public IPage<FriendVO> getFriends(Long userId, Page<?> page) {
        LambdaQueryWrapper<Friendship> wrapper = new LambdaQueryWrapper<Friendship>()
                .and(q -> q.eq(Friendship::getUserA, userId).or().eq(Friendship::getUserB, userId))
                .orderByDesc(Friendship::getCreateTime);

        Page<Friendship> friendshipPage = new Page<>(page.getCurrent(), page.getSize());
        IPage<Friendship> result = this.page(friendshipPage, wrapper);

        return result.convert(friendship -> convertFriendshipToVO(friendship, userId));
    }

    @Override
    public List<FriendVO> getAllFriends(Long userId) {
        LambdaQueryWrapper<Friendship> wrapper = new LambdaQueryWrapper<Friendship>()
                .and(q -> q.eq(Friendship::getUserA, userId).or().eq(Friendship::getUserB, userId))
                .orderByDesc(Friendship::getCreateTime);

        List<Friendship> friendships = this.list(wrapper);
        return friendships.stream()
                .map(f -> convertFriendshipToVO(f, userId))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void unfriend(Long userId, String friendId) {
        Long fId = IdUtils.fromExternalId(friendId);
        Long userA = Math.min(userId, fId);
        Long userB = Math.max(userId, fId);

        Friendship friendship = baseMapper.findByUsers(userA, userB, TenantContext.getTenantId());
        if (friendship == null) {
            throw new GeneralException(ResultEnum.NOT_FRIENDS);
        }

        this.removeById(friendship.getId());
        log.info("解除好友关系: userId={}, friendId={}", userId, fId);
    }

    @Override
    @Transactional
    public void updateRemark(Long userId, String friendId, String remark) {
        Long fId = IdUtils.fromExternalId(friendId);
        Long userA = Math.min(userId, fId);
        Long userB = Math.max(userId, fId);

        Friendship friendship = baseMapper.findByUsers(userA, userB, TenantContext.getTenantId());
        if (friendship == null) {
            throw new GeneralException(ResultEnum.NOT_FRIENDS);
        }

        friendship.setRemark(userId, remark);
        this.updateById(friendship);
        log.info("更新好友备注: userId={}, friendId={}, remark={}", userId, fId, remark);
    }

    @Override
    public int getPendingCount(Long userId) {
        return friendRequestMapper.countPendingReceived(userId, TenantContext.getTenantId());
    }

    @Override
    public List<UserSearchVO> searchUsers(Long userId, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 搜索用户（排除自己）
        LambdaQueryWrapper<Account> wrapper = new LambdaQueryWrapper<Account>()
                .ne(Account::getId, userId)
                .and(q -> q.like(Account::getUsername, keyword)
                        .or()
                        .like(Account::getNickname, keyword))
                .last("LIMIT 20");

        List<Account> accounts = accountService.list(wrapper);
        Long tenantId = TenantContext.getTenantId();

        return accounts.stream().map(account -> {
            Long accountId = account.getId();
            boolean isFriend = areFriends(userId, accountId);
            boolean hasPending = !isFriend && friendRequestMapper.countPendingBetween(userId, accountId, tenantId) > 0;

            return new UserSearchVO()
                    .setId(IdUtils.toExternalId(accountId))
                    .setUsername(account.getUsername())
                    .setAvatar(account.getAvatar())
                    .setNickname(account.getNickname())
                    .setIsFriend(isFriend)
                    .setHasPendingRequest(hasPending);
        }).collect(Collectors.toList());
    }

    private FriendRequestDetailVO convertRequestToVO(FriendRequest request, boolean isReceived) {
        Account requester = accountService.findAccountById(request.getRequesterId());
        Account addressee = accountService.findAccountById(request.getAddresseeId());

        FriendRequestDetailVO vo = new FriendRequestDetailVO()
                .setId(IdUtils.toExternalId(request.getId()))
                .setRequesterId(IdUtils.toExternalId(request.getRequesterId()))
                .setAddresseeId(IdUtils.toExternalId(request.getAddresseeId()))
                .setMessage(request.getMessage())
                .setStatus(request.getStatus())
                .setCreateTime(request.getCreateTime())
                .setUpdateTime(request.getUpdateTime());

        if (requester != null) {
            vo.setRequesterUsername(requester.getUsername())
                    .setRequesterAvatar(requester.getAvatar());
        }
        if (addressee != null) {
            vo.setAddresseeUsername(addressee.getUsername())
                    .setAddresseeAvatar(addressee.getAvatar());
        }

        return vo;
    }

    private FriendVO convertFriendshipToVO(Friendship friendship, Long currentUserId) {
        Long friendId = friendship.getFriendId(currentUserId);
        Account friend = accountService.findAccountById(friendId);

        FriendVO vo = new FriendVO()
                .setId(IdUtils.toExternalId(friendId))
                .setFriendshipId(IdUtils.toExternalId(friendship.getId()))
                .setRemark(friendship.getRemark(currentUserId))
                .setFriendSince(friendship.getCreateTime());

        if (friend != null) {
            vo.setUsername(friend.getUsername())
                    .setAvatar(friend.getAvatar())
                    .setNickname(friend.getNickname());
        }

        return vo;
    }
}
