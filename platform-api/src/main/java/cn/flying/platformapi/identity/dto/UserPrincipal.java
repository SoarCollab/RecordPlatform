package cn.flying.platformapi.identity.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
public class UserPrincipal implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String username;
    private String email;
    private List<String> roles;
    private List<String> permissions;

    private String tokenId;
    private Date issuedAt;
    private Date expireAt;

    private String clientId;
    private String scope;
}
