package com.wx.fbsir.business.websocket.task;

import com.wx.fbsir.business.websocket.domain.WsHostWhitelist;
import com.wx.fbsir.business.websocket.mapper.WsHostWhitelistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OpenClawHealthChecker} 单主机探测逻辑单元测试（不启动 Spring 容器）。
 */
@ExtendWith(MockitoExtension.class)
class OpenClawHealthCheckerTest {

    @Mock
    private WsHostWhitelistMapper wsHostWhitelistMapper;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private OpenClawHealthChecker openClawHealthChecker;

    private WsHostWhitelist host;

    @BeforeEach
    void setUp() {
        host = new WsHostWhitelist();
        host.setHostId("h1");
        host.setHealthCheckUrl("http://127.0.0.1:9/health");
        host.setOnlineStatus("offline");
    }

    @Test
    void checkSingleHost_2xx_updatesOnline() {
        when(restTemplate.exchange(eq(host.getHealthCheckUrl()), eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

        ReflectionTestUtils.invokeMethod(openClawHealthChecker, "checkSingleHost", host);

        ArgumentCaptor<WsHostWhitelist> cap = ArgumentCaptor.forClass(WsHostWhitelist.class);
        verify(wsHostWhitelistMapper).update(cap.capture());
        assertThat(cap.getValue().getOnlineStatus()).isEqualTo("online");
    }

    @Test
    void checkSingleHost_non2xx_setsOfflineWhenWasOnline() {
        host.setOnlineStatus("online");
        when(restTemplate.exchange(eq(host.getHealthCheckUrl()), eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(new ResponseEntity<>("", HttpStatus.BAD_GATEWAY));

        ReflectionTestUtils.invokeMethod(openClawHealthChecker, "checkSingleHost", host);

        ArgumentCaptor<WsHostWhitelist> cap = ArgumentCaptor.forClass(WsHostWhitelist.class);
        verify(wsHostWhitelistMapper).update(cap.capture());
        assertThat(cap.getValue().getOnlineStatus()).isEqualTo("offline");
    }

    @Test
    void checkSingleHost_exception_setsOfflineWhenWasOnline() {
        host.setOnlineStatus("online");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenThrow(new RestClientException("timeout"));

        ReflectionTestUtils.invokeMethod(openClawHealthChecker, "checkSingleHost", host);

        ArgumentCaptor<WsHostWhitelist> cap = ArgumentCaptor.forClass(WsHostWhitelist.class);
        verify(wsHostWhitelistMapper).update(cap.capture());
        assertThat(cap.getValue().getOnlineStatus()).isEqualTo("offline");
    }

    @Test
    void checkSingleHost_exception_noUpdateWhenAlreadyOffline() {
        host.setOnlineStatus("offline");
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenThrow(new RestClientException("timeout"));

        ReflectionTestUtils.invokeMethod(openClawHealthChecker, "checkSingleHost", host);

        verify(wsHostWhitelistMapper, never()).update(any());
    }

    @Test
    void checkSingleHost_unchangedOnline_skipsUpdate() {
        host.setOnlineStatus("online");
        when(restTemplate.exchange(eq(host.getHealthCheckUrl()), eq(HttpMethod.GET), eq(null), eq(String.class)))
                .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

        ReflectionTestUtils.invokeMethod(openClawHealthChecker, "checkSingleHost", host);

        verify(wsHostWhitelistMapper, never()).update(any());
    }

    @Test
    void checkSingleHost_emptyUrl_skips() {
        host.setHealthCheckUrl("");

        ReflectionTestUtils.invokeMethod(openClawHealthChecker, "checkSingleHost", host);

        verify(wsHostWhitelistMapper, never()).update(any());
        verify(restTemplate, never()).exchange(any(String.class), any(), any(), any(Class.class));
    }
}
