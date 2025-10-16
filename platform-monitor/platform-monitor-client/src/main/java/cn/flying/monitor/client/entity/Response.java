package cn.flying.monitor.client.entity;

import com.alibaba.fastjson2.JSONObject;

/**
 * @program: monitor
 * @description: 服务端请求响应类
 * @author: 王贝强
 * @create: 2024-07-14 18:39
 */
public record Response(int id, int code, Object data, String message) {
    public static Response errorResponse(Exception e) {
        return new Response(0, 500, null, e.getMessage());
    }

    public boolean success() {
        return code == 200;
    }

    public JSONObject asJSON() {
        return JSONObject.from(data);
    }

    public String asString() {
        return data.toString();
    }
}
