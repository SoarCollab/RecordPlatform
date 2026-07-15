package cn.flying.security;

import cn.flying.config.RateLimitClientIpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可信客户端 IP 解析器的安全边界测试。
 */
class TrustedClientIpResolverTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withBean(RateLimitClientIpProperties.class)
            .withBean(TrustedClientIpResolver.class);

    /**
     * 验证默认空 allowlist 只使用规范化 direct peer，并忽略所有转发头。
     */
    @Test
    void shouldUseCanonicalDirectPeerWhenNoProxyIsTrusted() {
        TrustedClientIpResolver resolver = resolver("");
        MockHttpServletRequest request = request("2001:0DB8:0:0:0:0:0:1");
        request.addHeader("X-Forwarded-For", "198.51.100.8");
        request.addHeader("X-Real-IP", "198.51.100.9");
        request.addHeader("Forwarded", "for=198.51.100.10");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8::1");
        assertThat(resolver.resolve(request("198.51.100.20"))).isEqualTo("198.51.100.20");
        assertThat(resolver.resolve(request("198.51.100.21"))).isEqualTo("198.51.100.21");
    }

    /**
     * 验证 IPv6 等价表示和 IPv4-mapped IPv6 归一到稳定身份。
     */
    @Test
    void shouldCanonicalizeEquivalentIpLiterals() {
        TrustedClientIpResolver resolver = resolver("");

        assertThat(resolver.resolve(request("2001:0db8:0:0:0:0:0:1")))
                .isEqualTo(resolver.resolve(request("2001:DB8::1")));
        assertThat(resolver.resolve(request("::ffff:192.0.2.1")))
                .isEqualTo(resolver.resolve(request("192.0.2.1")))
                .isEqualTo("192.0.2.1");
    }

    /**
     * 验证可信代理链从右向左跳过可信 hop，并忽略攻击者伪造的左端值。
     */
    @Test
    void shouldResolveForwardedForFromTrustedPeerRightToLeft() {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8, 2001:db8:ffff::/48");
        MockHttpServletRequest request = request("10.0.0.3");
        request.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.7, 10.0.0.2");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");

        MockHttpServletRequest spoofedLeft = request("10.0.0.3");
        spoofedLeft.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.8");
        assertThat(resolver.resolve(spoofedLeft)).isEqualTo("203.0.113.8");
    }

    /**
     * 验证整条 XFF 都是可信代理时返回最左端规范地址。
     */
    @Test
    void shouldUseLeftmostHopWhenEveryForwardedHopIsTrusted() {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8");
        MockHttpServletRequest request = request("10.0.0.3");
        request.addHeader("X-Forwarded-For", "10.1.1.1, 10.2.2.2");

        assertThat(resolver.resolve(request)).isEqualTo("10.1.1.1");
    }

    /**
     * 验证不可信立即对端无法通过合法-looking 转发头控制限流身份。
     */
    @Test
    void shouldIgnoreForwardingHeadersFromUntrustedPeer() {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8");
        MockHttpServletRequest request = request("198.51.100.24");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Real-IP", "203.0.113.8");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.24");
    }

    /**
     * 验证只有 XFF 完全缺失时才允许唯一合法的 X-Real-IP。
     */
    @Test
    void shouldUseRealIpOnlyWhenForwardedForIsAbsent() {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8");
        MockHttpServletRequest realIpOnly = request("10.0.0.3");
        realIpOnly.addHeader("X-Real-IP", " 203.0.113.9 ");
        assertThat(resolver.resolve(realIpOnly)).isEqualTo("203.0.113.9");

        MockHttpServletRequest invalidForwardedFor = request("10.0.0.3");
        invalidForwardedFor.addHeader("X-Forwarded-For", "not-an-ip");
        invalidForwardedFor.addHeader("X-Real-IP", "203.0.113.9");
        assertThat(resolver.resolve(invalidForwardedFor)).isEqualTo("10.0.0.3");
    }

    /**
     * 验证重复 header 行整体回退 direct peer，避免容器合并语义差异。
     */
    @Test
    void shouldRejectDuplicateForwardingHeaderLines() {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8");
        MockHttpServletRequest duplicateForwardedFor = request("10.0.0.3");
        duplicateForwardedFor.addHeader("X-Forwarded-For", "203.0.113.1");
        duplicateForwardedFor.addHeader("X-Forwarded-For", "203.0.113.2");
        assertThat(resolver.resolve(duplicateForwardedFor)).isEqualTo("10.0.0.3");

        MockHttpServletRequest duplicateRealIp = request("10.0.0.3");
        duplicateRealIp.addHeader("X-Real-IP", "203.0.113.1");
        duplicateRealIp.addHeader("X-Real-IP", "203.0.113.2");
        assertThat(resolver.resolve(duplicateRealIp)).isEqualTo("10.0.0.3");
    }

    /**
     * 验证非法、歧义或含控制字符的地址不能形成新桶。
     */
    @Test
    void shouldRejectInvalidForwardedIpLiterals() {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8");
        List<String> invalidValues = List.of(
                "",
                "unknown",
                "example.com",
                "https://203.0.113.1",
                "203.0.113.1:443",
                "[2001:db8::1]",
                "2001:db8::1%eth0",
                "203.000.113.1",
                "203.0.113.1\t",
                "203.0.113.1\u007f",
                "203.0.113.一");

        for (String invalidValue : invalidValues) {
            MockHttpServletRequest request = request("10.0.0.3");
            request.addHeader("X-Forwarded-For", invalidValue);
            assertThat(resolver.resolve(request)).as(invalidValue).isEqualTo("10.0.0.3");
        }
    }

    /**
     * 验证 XFF 的 16 hop 边界可用，17 hop 或超长输入稳定回退 direct peer。
     */
    @Test
    void shouldBoundForwardedForLengthAndHopCount() {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8");
        List<String> sixteenHops = new ArrayList<>();
        sixteenHops.add("203.0.113.7");
        IntStream.rangeClosed(1, 15).forEach(index -> sixteenHops.add("10.0.0." + index));
        MockHttpServletRequest accepted = request("10.0.0.20");
        accepted.addHeader("X-Forwarded-For", String.join(", ", sixteenHops));
        assertThat(resolver.resolve(accepted)).isEqualTo("203.0.113.7");

        MockHttpServletRequest tooMany = request("10.0.0.20");
        tooMany.addHeader("X-Forwarded-For", String.join(", ", sixteenHops) + ", 10.0.0.16");
        assertThat(resolver.resolve(tooMany)).isEqualTo("10.0.0.20");

        MockHttpServletRequest tooLong = request("10.0.0.20");
        tooLong.addHeader("X-Forwarded-For", "1".repeat(TrustedClientIpResolver.MAX_XFF_CHARS + 1));
        assertThat(resolver.resolve(tooLong)).isEqualTo("10.0.0.20");
    }

    /**
     * 验证空 hop 和非法 XFF 不会降级读取另一个调用者可控 header。
     */
    @Test
    void shouldRejectEntireForwardedChainWhenAnyHopIsInvalid() {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8");
        for (String chain : List.of(
                ",203.0.113.7",
                "203.0.113.7,",
                "203.0.113.7,,10.0.0.2",
                "203.0.113.7,not-an-ip,10.0.0.2")) {
            MockHttpServletRequest request = request("10.0.0.3");
            request.addHeader("X-Forwarded-For", chain);
            request.addHeader("X-Real-IP", "203.0.113.9");
            assertThat(resolver.resolve(request)).as(chain).isEqualTo("10.0.0.3");
        }
    }

    /**
     * 验证非法或缺失 direct peer 使用单一未知桶且绝不读取转发头。
     */
    @Test
    void shouldUseUnknownBucketForInvalidDirectPeer() {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8");
        for (String peer : new String[]{null, "", "localhost", "10.0.0.1:80", "[::1]", "fe80::1%eth0"}) {
            MockHttpServletRequest request = request(peer);
            request.addHeader("X-Forwarded-For", "203.0.113.7");
            assertThat(resolver.resolve(request)).isEqualTo("unknown-peer");
        }
        assertThat(resolver.resolve(null)).isEqualTo("unknown-peer");
    }

    /**
     * 验证 IPv4/IPv6 CIDR 仅信任命中网段和相同地址族的立即对端。
     */
    @Test
    void shouldMatchTrustedIpv4AndIpv6Networks() {
        TrustedClientIpResolver resolver = resolver("10.0.0.0/8,2001:db8::/32");

        MockHttpServletRequest ipv4Boundary = request("10.255.255.255");
        ipv4Boundary.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(resolver.resolve(ipv4Boundary)).isEqualTo("203.0.113.7");

        MockHttpServletRequest outside = request("11.0.0.1");
        outside.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(resolver.resolve(outside)).isEqualTo("11.0.0.1");

        MockHttpServletRequest ipv6 = request("2001:db8:ffff::1");
        ipv6.addHeader("X-Forwarded-For", "2001:db9::1");
        assertThat(resolver.resolve(ipv6)).isEqualTo("2001:db9::1");
    }

    /**
     * 验证任一非法可信代理配置都会整体 fail fast，且异常不回显完整配置。
     */
    @Test
    void shouldFailFastForInvalidTrustedProxyConfiguration() {
        List<String> invalidConfigurations = List.of(
                "example.com",
                "https://10.0.0.1",
                "10.0.0.1:80",
                "[2001:db8::1]",
                "fe80::1%eth0",
                "0.0.0.0/0",
                "::/0",
                "10.0.0.0/33",
                "2001:db8::/129",
                "10.0.0.0/-1",
                "10.0.0.0/08",
                "10.0.0.0/8/1",
                "10.0.0.1,,10.0.0.2",
                "::ffff:192.0.2.0/120",
                "::ffff:192.0.2.1/32");

        for (String invalidConfiguration : invalidConfigurations) {
            assertThatThrownBy(() -> resolver(invalidConfiguration))
                    .as(invalidConfiguration)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("spring.web.rate-limit.client-ip.trusted-proxies")
                    .hasMessageNotContaining(invalidConfiguration);
        }

        String tooMany = IntStream.rangeClosed(1, TrustedClientIpResolver.MAX_TRUSTED_PROXY_RANGES + 1)
                .mapToObj(index -> "10.0.0." + index)
                .collect(Collectors.joining(","));
        assertThatThrownBy(() -> resolver(tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver("1".repeat(
                TrustedClientIpResolver.MAX_TRUSTED_PROXY_CONFIG_CHARS + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证 Spring 配置绑定默认空值和合法 CIDR，并拒绝非法 CIDR 与转发头预处理策略。
     */
    @Test
    void shouldBindConfigurationAndRejectUnsafeStartupValues() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
        contextRunner
                .withPropertyValues(
                        "spring.web.rate-limit.client-ip.trusted-proxies=10.0.0.0/8,2001:db8::/32",
                        "server.forward-headers-strategy=none")
                .run(context -> assertThat(context).hasNotFailed());
        contextRunner
                .withPropertyValues("spring.web.rate-limit.client-ip.trusted-proxies=0.0.0.0/0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("server.forward-headers-strategy=native")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("server.tomcat.remoteip.remote-ip-header=x-forwarded-for")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("server.tomcat.remoteip.protocol-header=x-forwarded-proto")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 构造使用给定可信代理配置的解析器。
     */
    private TrustedClientIpResolver resolver(String trustedProxies) {
        RateLimitClientIpProperties properties = new RateLimitClientIpProperties();
        properties.setTrustedProxies(trustedProxies);
        return new TrustedClientIpResolver(properties, "none", "", "");
    }

    /**
     * 构造具有指定直接对端地址的 servlet 请求。
     */
    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
