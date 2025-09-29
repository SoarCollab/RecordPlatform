package cn.flying.identity.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAgentUtilsTest {

    private static HttpServletRequest mockRequest(String userAgent) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("User-Agent")).thenReturn(userAgent);
        return request;
    }

    @Test
    @DisplayName("returns Unknown when request missing")
    void getUserAgentShouldReturnUnknownWhenRequestNull() {
        assertEquals("Unknown", UserAgentUtils.getUserAgent(null));
    }

    @Test
    @DisplayName("parses Chrome browser information")
    void getBrowserShouldIdentifyChrome() {
        HttpServletRequest request = mockRequest("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36");
        assertEquals("Google Chrome", UserAgentUtils.getBrowser(request));
    }

    @Test
    @DisplayName("parses Safari browser without Chrome token")
    void getBrowserFromUserAgentShouldPreferSafari() {
        assertEquals("Safari", UserAgentUtils.getBrowserFromUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Version/15.1 Safari/605.1.15"));
    }

    @Test
    @DisplayName("detects Android operating system")
    void getOperatingSystemShouldIdentifyAndroid() {
        HttpServletRequest request = mockRequest("Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 Chrome/98.0.4758.102 Mobile Safari/537.36");
        assertEquals("Android", UserAgentUtils.getOperatingSystem(request));
    }

    @Test
    @DisplayName("detects mobile and tablet devices")
    void deviceDetectionShouldWorkForMobileAndTablet() {
        assertTrue(UserAgentUtils.isMobileFromUserAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) Mobile/15E148"));
        assertEquals("Tablet", UserAgentUtils.getDeviceTypeFromUserAgent("Mozilla/5.0 (iPad; CPU OS 15_0 like Mac OS X) AppleWebKit/605.1.15"));
        assertEquals("Desktop", UserAgentUtils.getDeviceTypeFromUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"));
    }

    @Test
    @DisplayName("formats client info summary")
    void getClientInfoShouldCombineBrowserOsAndDevice() {
        HttpServletRequest request = mockRequest("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36");
        assertEquals("Google Chrome on Windows 10 (Desktop)", UserAgentUtils.getClientInfo(request));
    }

    @Test
    @DisplayName("generates stable fingerprint")
    void generateDeviceFingerprintShouldBeDeterministic() {
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36";
        String expected = String.valueOf("Google Chrome|Windows 10|Desktop".hashCode());
        assertEquals(expected, UserAgentUtils.generateDeviceFingerprint(userAgent));
    }

    @Test
    @DisplayName("identifies bot user agents")
    void isBotUserAgentShouldDetectCrawler() {
        assertTrue(UserAgentUtils.isBotUserAgent("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"));
        assertFalse(UserAgentUtils.isBotUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36"));
    }

    @Test
    @DisplayName("falls back to Unknown when header empty")
    void getUserAgentShouldHandleEmptyHeader() {
        HttpServletRequest request = mockRequest(" ");
        assertEquals("Unknown", UserAgentUtils.getUserAgent(request));
        verify(request).getHeader("User-Agent");
    }
}
