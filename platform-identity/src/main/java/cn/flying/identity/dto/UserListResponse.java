package cn.flying.identity.dto;

import cn.flying.identity.vo.AccountVO;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 用户列表响应DTO
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
public class UserListResponse {

    private List<AccountVO> content;

    private long totalElements;

    private long totalPages;

    private int number;

    private int size;

    private boolean first;

    private boolean last;
}
