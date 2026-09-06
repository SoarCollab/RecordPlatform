package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** Sanitized tenant member mutation audit record. */
@Getter
@Setter
@Accessors(chain = true)
@TableName("account_member_audit")
public class AccountMemberAudit {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long actorId;
    private Long targetAccountId;
    private Long invitationId;
    private String action;
    private String oldValue;
    private String newValue;
    private String reason;
    private LocalDateTime createTime;
}
