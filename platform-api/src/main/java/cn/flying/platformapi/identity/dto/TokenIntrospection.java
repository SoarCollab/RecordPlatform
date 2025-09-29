package cn.flying.platformapi.identity.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Data
public class TokenIntrospection implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private boolean active;
    private Long subject;     // userId
    private String clientId;
    private String scope;
    private Date issuedAt;
    private Date expireAt;
    private List<String> roles;
    private List<String> permissions;
    private String issuer;
}
