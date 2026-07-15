package cn.flying.security;

import cn.flying.config.RateLimitClientIpProperties;
import io.netty.util.NetUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

/**
 * 基于直接对端和显式可信代理网段解析规范化客户端 IP。
 */
@Component
public class TrustedClientIpResolver {

    static final int MAX_XFF_CHARS = 1024;
    static final int MAX_XFF_HOPS = 16;
    static final int MAX_IP_LITERAL_CHARS = 64;
    static final int MAX_TRUSTED_PROXY_CONFIG_CHARS = 4096;
    static final int MAX_TRUSTED_PROXY_RANGES = 64;

    private static final String UNKNOWN_PEER = "unknown-peer";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";

    private final List<CidrBlock> trustedProxies;

    /**
     * 在启动时校验容器转发头策略并编译不可变可信代理网段。
     */
    public TrustedClientIpResolver(
            RateLimitClientIpProperties properties,
            @Value("${server.forward-headers-strategy:none}") String forwardHeadersStrategy,
            @Value("${server.tomcat.remoteip.remote-ip-header:}") String remoteIpHeader,
            @Value("${server.tomcat.remoteip.protocol-header:}") String protocolHeader) {
        if (!"none".equalsIgnoreCase(forwardHeadersStrategy)) {
            throw new IllegalArgumentException(
                    "server.forward-headers-strategy must be none when trusted client IP resolution is enabled");
        }
        if (hasText(remoteIpHeader) || hasText(protocolHeader)) {
            throw new IllegalArgumentException(
                    "server.tomcat.remoteip header rewriting must be disabled for trusted client IP resolution");
        }
        this.trustedProxies = parseTrustedProxies(properties.getTrustedProxies());
    }

    /**
     * 解析请求的可信客户端 IP；任何不明确输入都稳定回退到直接对端。
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN_PEER;
        }

        NumericIp peer = parseNumericIp(request.getRemoteAddr());
        if (peer == null) {
            return UNKNOWN_PEER;
        }
        if (trustedProxies.isEmpty() || !isTrusted(peer)) {
            return peer.canonical();
        }

        HeaderValue forwardedFor = readSingleHeader(request, X_FORWARDED_FOR);
        if (forwardedFor.state() == HeaderState.INVALID) {
            return peer.canonical();
        }
        if (forwardedFor.state() == HeaderState.PRESENT) {
            NumericIp forwardedClient = parseForwardedFor(forwardedFor.value());
            return forwardedClient == null ? peer.canonical() : forwardedClient.canonical();
        }

        HeaderValue realIp = readSingleHeader(request, X_REAL_IP);
        if (realIp.state() != HeaderState.PRESENT
                || realIp.value() == null
                || realIp.value().length() > MAX_XFF_CHARS) {
            return peer.canonical();
        }
        NumericIp resolvedRealIp = parseNumericIp(trimAsciiSpaces(realIp.value()));
        return resolvedRealIp == null ? peer.canonical() : resolvedRealIp.canonical();
    }

    /**
     * 解析有界 XFF 链，并从右向左选择最靠近可信代理的首个不可信地址。
     */
    private NumericIp parseForwardedFor(String headerValue) {
        if (headerValue == null || headerValue.isEmpty() || headerValue.length() > MAX_XFF_CHARS) {
            return null;
        }

        List<NumericIp> hops = new ArrayList<>(Math.min(MAX_XFF_HOPS, 4));
        int start = 0;
        while (start <= headerValue.length()) {
            if (hops.size() == MAX_XFF_HOPS) {
                return null;
            }
            int separator = headerValue.indexOf(',', start);
            int end = separator >= 0 ? separator : headerValue.length();
            String token = trimAsciiSpaces(headerValue.substring(start, end));
            NumericIp hop = parseNumericIp(token);
            if (hop == null) {
                return null;
            }
            hops.add(hop);
            if (separator < 0) {
                break;
            }
            start = separator + 1;
        }

        for (int index = hops.size() - 1; index >= 0; index--) {
            NumericIp hop = hops.get(index);
            if (!isTrusted(hop)) {
                return hop;
            }
        }
        return hops.getFirst();
    }

    /**
     * 读取恰好一个 header 行，避免容器对重复 header 的合并差异。
     */
    private HeaderValue readSingleHeader(HttpServletRequest request, String headerName) {
        Enumeration<String> values = request.getHeaders(headerName);
        if (values == null || !values.hasMoreElements()) {
            return HeaderValue.absent();
        }
        String value = values.nextElement();
        if (values.hasMoreElements()) {
            return HeaderValue.invalid();
        }
        return HeaderValue.present(value);
    }

    /**
     * 判断规范化地址是否位于任一可信代理网段。
     */
    private boolean isTrusted(NumericIp ip) {
        return trustedProxies.stream().anyMatch(network -> network.contains(ip.address));
    }

    /**
     * 启动时解析完整可信代理配置；任一非法项都会阻止应用启动。
     */
    private List<CidrBlock> parseTrustedProxies(String configuredRanges) {
        String raw = configuredRanges == null ? "" : configuredRanges;
        if (raw.length() > MAX_TRUSTED_PROXY_CONFIG_CHARS) {
            throw invalidConfiguration(0, "configuration is too long");
        }
        if (raw.isBlank()) {
            return List.of();
        }

        String[] entries = raw.split(",", -1);
        if (entries.length > MAX_TRUSTED_PROXY_RANGES) {
            throw invalidConfiguration(0, "too many ranges");
        }

        List<CidrBlock> networks = new ArrayList<>(entries.length);
        for (int index = 0; index < entries.length; index++) {
            networks.add(parseCidrBlock(trimAsciiSpaces(entries[index]), index + 1));
        }
        return List.copyOf(networks);
    }

    /**
     * 将单个数字 IP/CIDR 编译为按位匹配结构。
     */
    private CidrBlock parseCidrBlock(String configuredRange, int itemIndex) {
        if (configuredRange == null || configuredRange.isEmpty()) {
            throw invalidConfiguration(itemIndex, "empty range");
        }
        int slash = configuredRange.indexOf('/');
        if (slash != configuredRange.lastIndexOf('/')) {
            throw invalidConfiguration(itemIndex, "invalid prefix");
        }

        String literal = slash < 0 ? configuredRange : configuredRange.substring(0, slash);
        NumericIp ip = parseNumericIp(literal);
        if (ip == null) {
            throw invalidConfiguration(itemIndex, "invalid numeric IP");
        }
        byte[] configuredAddress = NetUtil.createByteArrayFromIpAddressString(literal);
        if (slash >= 0 && isIpv4MappedIpv6(configuredAddress)) {
            throw invalidConfiguration(itemIndex, "mapped IPv6 CIDR is ambiguous");
        }

        int addressBits = ip.address().length * Byte.SIZE;
        int prefixLength = addressBits;
        if (slash >= 0) {
            String prefix = configuredRange.substring(slash + 1);
            prefixLength = parsePrefix(prefix, addressBits, itemIndex);
        }
        return new CidrBlock(ip.address(), prefixLength);
    }

    /**
     * 解析非零十进制 CIDR 前缀并拒绝全网通配范围。
     */
    private int parsePrefix(String value, int addressBits, int itemIndex) {
        if (value.isEmpty() || (value.length() > 1 && value.charAt(0) == '0')) {
            throw invalidConfiguration(itemIndex, "invalid prefix");
        }
        int parsed = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw invalidConfiguration(itemIndex, "invalid prefix");
            }
            parsed = parsed * 10 + character - '0';
            if (parsed > addressBits) {
                throw invalidConfiguration(itemIndex, "prefix out of range");
            }
        }
        if (parsed == 0) {
            throw invalidConfiguration(itemIndex, "wildcard range is forbidden");
        }
        return parsed;
    }

    /**
     * 严格解析数字 IPv4/IPv6，并将 IPv4-mapped IPv6 折叠为 IPv4。
     */
    private NumericIp parseNumericIp(String value) {
        if (!hasValidLiteralCharacters(value)) {
            return null;
        }
        if (!value.contains(":")) {
            if (!isStrictIpv4(value)) {
                return null;
            }
        } else if (value.contains(".")) {
            String embeddedIpv4 = value.substring(value.lastIndexOf(':') + 1);
            if (!isStrictIpv4(embeddedIpv4)) {
                return null;
            }
        }

        byte[] address = NetUtil.createByteArrayFromIpAddressString(value);
        if (address == null) {
            return null;
        }
        if (isIpv4MappedIpv6(address)) {
            address = Arrays.copyOfRange(address, 12, 16);
        }
        return new NumericIp(address, NetUtil.bytesToIpAddress(address));
    }

    /**
     * 在调用数字解析器前拒绝空白、控制字符、非 ASCII、端口、括号和 zone id。
     */
    private boolean hasValidLiteralCharacters(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_IP_LITERAL_CHARS) {
            return false;
        }
        boolean hasSeparator = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F'
                    || character == '.'
                    || character == ':';
            if (!allowed) {
                return false;
            }
            hasSeparator |= character == '.' || character == ':';
        }
        return hasSeparator;
    }

    /**
     * 拒绝前导零及非四段十进制 IPv4 表示。
     */
    private boolean isStrictIpv4(String value) {
        int segments = 0;
        int start = 0;
        while (start <= value.length()) {
            int separator = value.indexOf('.', start);
            int end = separator >= 0 ? separator : value.length();
            if (end == start || end - start > 3 || end - start > 1 && value.charAt(start) == '0') {
                return false;
            }
            int octet = 0;
            for (int index = start; index < end; index++) {
                char character = value.charAt(index);
                if (character < '0' || character > '9') {
                    return false;
                }
                octet = octet * 10 + character - '0';
            }
            if (octet > 255 || ++segments > 4) {
                return false;
            }
            if (separator < 0) {
                break;
            }
            start = separator + 1;
        }
        return segments == 4;
    }

    /**
     * 判断 16 字节地址是否为 IPv4-mapped IPv6。
     */
    private boolean isIpv4MappedIpv6(byte[] address) {
        if (address == null || address.length != 16) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (address[index] != 0) {
                return false;
            }
        }
        return address[10] == (byte) 0xff && address[11] == (byte) 0xff;
    }

    /**
     * 判断容器 header 配置是否包含会启用 RemoteIpValve 的有效文本。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 仅移除 header/CIDR 项外围的 ASCII 空格，不吞掉 tab 等控制字符。
     */
    private String trimAsciiSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * 构造不回显配置原文的启动失败异常。
     */
    private IllegalArgumentException invalidConfiguration(int itemIndex, String reason) {
        String item = itemIndex > 0 ? " item " + itemIndex : "";
        return new IllegalArgumentException(
                "spring.web.rate-limit.client-ip.trusted-proxies" + item + " " + reason);
    }

    private record NumericIp(byte[] address, String canonical) {

        /**
         * 防御性复制地址字节，避免不可变解析结果被外部修改。
         */
        private NumericIp {
            address = address.clone();
        }

        /**
         * 返回用于只读匹配的地址副本。
         */
        @Override
        public byte[] address() {
            return address.clone();
        }
    }

    private record CidrBlock(byte[] network, int prefixLength) {

        /**
         * 掩码化并复制网络地址，确保网段快照不可变。
         */
        private CidrBlock {
            network = network.clone();
            int wholeBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            if (remainingBits > 0) {
                int mask = 0xff << (Byte.SIZE - remainingBits) & 0xff;
                network[wholeBytes] = (byte) ((network[wholeBytes] & 0xff) & mask);
                wholeBytes++;
            }
            Arrays.fill(network, wholeBytes, network.length, (byte) 0);
        }

        /**
         * 按 CIDR 前缀匹配同地址族的规范化地址。
         */
        private boolean contains(byte[] address) {
            if (network.length != address.length) {
                return false;
            }
            int wholeBytes = prefixLength / Byte.SIZE;
            for (int index = 0; index < wholeBytes; index++) {
                if (network[index] != address[index]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % Byte.SIZE;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (Byte.SIZE - remainingBits) & 0xff;
            return ((network[wholeBytes] & 0xff) & mask)
                    == ((address[wholeBytes] & 0xff) & mask);
        }

        /**
         * 返回网络地址副本，保持 record 的数组成员不可变。
         */
        @Override
        public byte[] network() {
            return network.clone();
        }
    }

    private enum HeaderState {
        ABSENT,
        PRESENT,
        INVALID
    }

    private record HeaderValue(HeaderState state, String value) {

        /**
         * 构造缺失 header 状态。
         */
        private static HeaderValue absent() {
            return new HeaderValue(HeaderState.ABSENT, null);
        }

        /**
         * 构造单值 header 状态。
         */
        private static HeaderValue present(String value) {
            return new HeaderValue(HeaderState.PRESENT, value);
        }

        /**
         * 构造重复 header 的非法状态。
         */
        private static HeaderValue invalid() {
            return new HeaderValue(HeaderState.INVALID, null);
        }
    }
}
