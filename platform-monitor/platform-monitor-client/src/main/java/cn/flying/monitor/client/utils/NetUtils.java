package cn.flying.monitor.client.utils;

import cn.flying.monitor.client.entity.BaseDetail;
import cn.flying.monitor.client.entity.ConnectionConfig;
import cn.flying.monitor.client.entity.Response;
import cn.flying.monitor.client.entity.RuntimeDetail;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * @program: monitor
 * @description: 向服务端发送http请求的工具类
 * @author: 王贝强
 * @create: 2024-07-14 18:36
 */
@Slf4j
@Component
public class NetUtils {

    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int REQUEST_TIMEOUT_SECONDS = 5;
    private static final int MAX_RETRIES = 3;
    private static final int BACKOFF_BASE_MS = 500;
    private static final int PENDING_MAX = 500;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // 运行时上报本地缓冲队列（断网/失败时入队，成功后回放，限制最大长度）
    private final Deque<String> pendingRuntimeQueue = new ConcurrentLinkedDeque<>();
    // 基础信息上报失败时的暂存（仅保留最新一次）
    private volatile String pendingBaseDetailJson;
    @Lazy
    @Resource
    ConnectionConfig config;

    /**
     * 客户端向服务端执行注册
     *
     * @param address 服务端基础地址
     * @param token   注册令牌
     * @return 是否注册成功
     */
    public boolean registerToServer(String address, String token) {
        log.info("正在向服务端注册，请稍等。。。");
        Response response = this.doGet("/register", address, token);
        if (response.success()) {
            log.info("客户端注册已完成！");
        } else {
            log.error("客户端注册失败：{}", response.message());
        }
        return response.success();
    }

    /**
     * 发送 GET 请求（带 Token），并增强错误信息输出
     *
     * @param url     路径（以 / 开头或不以 / 开头均可）
     * @param address 基础地址，例如 http://127.0.0.1:8001
     * @param token   认证 Token
     * @return 统一响应对象
     */
    private Response doGet(String url, String address, String token) {
        return this.getWithRetry(url, address, token);
    }

    /**
     * 规范化构建 URI，自动处理基础地址尾部斜杠与路径前缀
     *
     * @param base 基础地址
     * @param path 路径
     * @return 规范化后的 URI
     */
    private URI buildUri(String base, String path) {
        String b = base == null ? "" : base.trim();
        String p = path == null ? "" : path.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!p.startsWith("/")) p = "/" + p;
        return URI.create(b + p);
    }

    public void updateBaseDetails(BaseDetail detail) {
        String json = JSON.toJSONString(detail);
        Response response = this.postWithRetry("/detail", json, true);
        if (response.success()) {
            log.info("系统基本信息更新完成！");
            // 成功后尝试回放历史失败记录
            pendingBaseDetailJson = null;
            this.flushPending();
        } else {
            log.error("系统基本信息更新失败：{}", response.message());
            // 暂存基础信息（只保留最新一份）
            pendingBaseDetailJson = json;
        }
    }

    /**
     * 发送 POST 请求（JSON），并增强错误信息输出
     *
     * @param url  路径（以 / 开头或不以 / 开头均可）
     * @param data 请求体对象
     * @return 统一响应对象
     */
    private Response doPost(String url, Object data) {
        String rawData = JSON.toJSONString(data);
        return this.postWithRetry(url, rawData, true);
    }

    public void updateRuntimeDetails(RuntimeDetail detail) {
        String json = JSON.toJSONString(detail);
        Response response = this.postWithRetry("/runtime", json, true);
        if (!response.success()) {
            log.info("更新系统运行时状态失败, 将加入重试队列。服务器响应：{}", response.message());
            this.enqueueRuntime(json);
        } else {
            // 成功后尝试回放历史失败记录
            this.flushPending();
        }
    }

    private Response doGet(String url) {
        return this.doGet(url, config.getAddress(), config.getToken());
    }

    /**
     * 发送 POST（JSON）带超时/重试/401自动注册与指数退避
     *
     * @param url  相对路径（以 / 开头或不以 / 开头均可）
     * @param json JSON字符串
     * @param tryRegister401 是否在401时自动尝试注册并重试
     * @return 统一响应
     */
    private Response postWithRetry(String url, String json, boolean tryRegister401) {
        Response last = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(this.buildUri(config.getAddress(), "/monitor" + url))
                        .header("Authorization", config.getToken())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                Response parsed = this.parseResponse(response);
                if (parsed.code() == 401 && tryRegister401) {
                    log.warn("POST {} 返回 401，尝试自动注册后重试(第{}次)", url, i + 1);
                    boolean ok = this.registerToServer(config.getAddress(), config.getToken());
                    if (!ok) return parsed;
                    continue;
                }
                if (parsed.success()) return parsed;
                // 5xx 或网络抖动，进行退避重试
                if (response.statusCode() >= 500) {
                    last = parsed;
                    this.sleepBackoff(i);
                    continue;
                }
                return parsed;
            } catch (Exception e) {
                log.warn("POST {} 失败(第{}次)：{}", url, i + 1, e.toString());
                last = Response.errorResponse(e);
                this.sleepBackoff(i);
            }
        }
        return last == null ? new Response(0, 500, null, "请求失败") : last;
    }

    /**
     * 发送 GET 带超时/重试/401自动注册与指数退避
     *
     * @param url 相对路径
     * @param address 基础地址
     * @param token 认证Token
     * @return 统一响应
     */
    private Response getWithRetry(String url, String address, String token) {
        Response last = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder().GET()
                        .uri(this.buildUri(address, "/monitor" + url))
                        .header("Authorization", token)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                Response parsed = this.parseResponse(response);
                if (parsed.code() == 401 && address.equals(config.getAddress())) {
                    log.warn("GET {} 返回 401，尝试自动注册后重试(第{}次)", url, i + 1);
                    boolean ok = this.registerToServer(address, token);
                    if (!ok) return parsed;
                    continue;
                }
                if (parsed.success()) return parsed;
                if (response.statusCode() >= 500) {
                    last = parsed;
                    this.sleepBackoff(i);
                    continue;
                }
                return parsed;
            } catch (Exception e) {
                log.warn("GET {} 失败(第{}次)：{}", url, i + 1, e.toString());
                last = Response.errorResponse(e);
                this.sleepBackoff(i);
            }
        }
        return last == null ? new Response(0, 500, null, "请求失败") : last;
    }

    /**
     * 解析HTTP响应为统一响应对象，非JSON时回传原始正文
     *
     * @param response HttpResponse
     * @return 统一响应
     */
    private Response parseResponse(HttpResponse<String> response) {
        try {
            return JSON.parseObject(response.body(), Response.class);
        } catch (Exception parseEx) {
            log.error("响应解析失败，status={}，body={}", response.statusCode(), response.body());
            return new Response(0, response.statusCode(), response.body(), "非JSON响应");
        }
    }

    /**
     * 指数退避休眠
     *
     * @param attempt 第几次重试，从0开始
     */
    private void sleepBackoff(int attempt) {
        try {
            long ms = (long) (BACKOFF_BASE_MS * Math.pow(2, attempt));
            ms = Math.min(ms, 5000);
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * 入队运行时JSON（上限PENDING_MAX，超限丢弃最早项）
     *
     * @param json 运行时数据JSON
     */
    private void enqueueRuntime(String json) {
        while (pendingRuntimeQueue.size() >= PENDING_MAX) {
            pendingRuntimeQueue.pollFirst();
        }
        pendingRuntimeQueue.addLast(json);
        log.debug("运行时上报入队，当前队列大小: {}", pendingRuntimeQueue.size());
    }

    /**
     * 回放失败上报（先基础信息，再运行时），单次限额避免阻塞
     */
    private void flushPending() {
        // 先回放基础信息
        if (pendingBaseDetailJson != null) {
            Response r = this.postWithRetry("/detail", pendingBaseDetailJson, true);
            if (r.success()) {
                pendingBaseDetailJson = null;
            } else {
                return; // 基础信息未成功，先不继续回放运行时
            }
        }
        // 回放运行时：单次最多回放20条，避免长时间阻塞
        for (int i = 0; i < 20; i++) {
            String head = pendingRuntimeQueue.peekFirst();
            if (head == null) break;
            Response r = this.postWithRetry("/runtime", head, false);
            if (r.success()) {
                pendingRuntimeQueue.pollFirst();
            } else {
                break;
            }
        }
    }
}
