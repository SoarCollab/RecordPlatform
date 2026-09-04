package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** Tenant-bound, digest-only account invitation. */
@Getter
@Setter
@Accessors(chain = true)
@TableName("account_invitation")
public class AccountInvitation {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String tokenHash;
    private String email;
    private String role;
    private String status;
    private Long invitedBy;
    private LocalDateTime expiresAt;
    private Long acceptedBy;
    private LocalDateTime acceptedAt;
    private Long revokedBy;
    private LocalDateTime revokedAt;
    private String revokeReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
