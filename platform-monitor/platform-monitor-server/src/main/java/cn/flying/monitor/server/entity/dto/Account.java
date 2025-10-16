package cn.flying.monitor.server.entity.dto;

import cn.flying.monitor.server.entity.BaseData;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 数据库中的用户信息
 */
@Data
@TableName("account")
@AllArgsConstructor
@NoArgsConstructor
public class Account implements BaseData {
    @TableId(type = IdType.AUTO)
    Integer id;
    String username;
    String password;
    String email;
    String role;
    String clients;
    Date registerTime;

    /**
     * 认证类型：local-本地用户名密码认证，oauth-OAuth2单点登录认证
     */
    String authType;

    /**
     * OAuth2提供者标识，如platform-identity
     */
    String oauthProvider;

    /**
     * OAuth2提供者系统中的用户ID
     */
    Long oauthUserId;

    public List<Integer> getClientList() {
        if (this.clients == null) return Collections.emptyList();
        return JSONArray.parse(this.clients).toList(Integer.class);
    }
}
