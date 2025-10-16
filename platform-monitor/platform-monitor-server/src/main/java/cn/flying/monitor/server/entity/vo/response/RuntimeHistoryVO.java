package cn.flying.monitor.server.entity.vo.response;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.LinkedList;
import java.util.List;

/**
 * @program: monitor
 * @description: 历史监控数据返回VO
 * @author: 王贝强
 * @create: 2024-07-22 10:10
 */
@Data
public class RuntimeHistoryVO {
    double disk;
    double memory;
    List<JSONObject> list=new LinkedList<>();
}
