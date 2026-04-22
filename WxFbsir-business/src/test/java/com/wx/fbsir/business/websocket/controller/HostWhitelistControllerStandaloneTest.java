package com.wx.fbsir.business.websocket.controller;

import com.wx.fbsir.business.websocket.domain.WsHostWhitelist;
import com.wx.fbsir.business.websocket.mapper.WsHostWhitelistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link HostWhitelistController} 独立 MockMvc 测试（不启动 Spring 容器、不经过 Security 代理，与 @PreAuthorize 无关）。
 */
@ExtendWith(MockitoExtension.class)
class HostWhitelistControllerStandaloneTest {

    @Mock
    private WsHostWhitelistMapper wsHostWhitelistMapper;

    @Mock
    private RestTemplate restTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        HostWhitelistController controller = new HostWhitelistController();
        ReflectionTestUtils.setField(controller, "wsHostWhitelistMapper", wsHostWhitelistMapper);
        ReflectionTestUtils.setField(controller, "restTemplate", restTemplate);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void manualHealthCheck_returnsOnlineWhen2xx() throws Exception {
        WsHostWhitelist host = new WsHostWhitelist();
        host.setId(10L);
        host.setHostId("hermes-t");
        host.setHostName("n");
        host.setHostType("hermes");
        host.setHealthCheckUrl("http://127.0.0.1:8642/health");
        host.setOnlineStatus("offline");

        when(wsHostWhitelistMapper.selectById(10L)).thenReturn(host);
        when(restTemplate.exchange(eq(host.getHealthCheckUrl()), eq(HttpMethod.GET), isNull(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"status\":\"ok\"}", HttpStatus.OK));

        mockMvc.perform(get("/business/host/whitelist/health-check/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.onlineStatus").value("online"));
    }

    @Test
    void manualHealthCheck_rejectsEngineType() throws Exception {
        WsHostWhitelist host = new WsHostWhitelist();
        host.setId(11L);
        host.setHostType("engine");
        host.setHealthCheckUrl("http://x");
        when(wsHostWhitelistMapper.selectById(11L)).thenReturn(host);

        mockMvc.perform(get("/business/host/whitelist/health-check/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }
}
