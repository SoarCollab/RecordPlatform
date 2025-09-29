package cn.flying.platformapi.identity.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
public class UserInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String username;
    private String email;
    private String avatar;
    private String role;
    private Date registerTime;
}
