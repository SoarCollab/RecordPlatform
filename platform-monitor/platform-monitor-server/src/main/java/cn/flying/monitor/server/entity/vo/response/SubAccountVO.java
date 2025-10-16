package cn.flying.monitor.server.entity.vo.response;

import com.alibaba.fastjson2.JSONArray;
import lombok.Data;

/**
 * @program: monitor
 * @description: 查询所有子账户返回VO
 * @author: 王贝强
 * @create: 2024-07-25 14:24
 */
@Data
public class SubAccountVO {
    int id;
    String username;
    String email;
    JSONArray clients;

}
