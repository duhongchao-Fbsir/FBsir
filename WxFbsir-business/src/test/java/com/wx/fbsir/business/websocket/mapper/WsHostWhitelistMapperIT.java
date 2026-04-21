package com.wx.fbsir.business.websocket.mapper;

import com.wx.fbsir.business.websocket.WebsocketMapperTestApplication;
import com.wx.fbsir.business.websocket.domain.WsHostWhitelist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WsHostWhitelistMapper#selectHostsForHttpHealthCheck()} 集成测试（H2 + 真实 XML）。
 * <p>默认 Surefire 命名不包含 {@code *IT}；且当前测试 Application 与全量 Mapper 扫描存在冲突，
 * 需在专用 profile 中单独运行或后续收紧 {@code WebsocketMapperTestApplication} 后再纳入 CI。</p>
 */
@SpringBootTest(classes = WebsocketMapperTestApplication.class)
@ActiveProfiles("test")
@Transactional
class WsHostWhitelistMapperIT {

    @Autowired
    private WsHostWhitelistMapper wsHostWhitelistMapper;

    @BeforeEach
    void setUp() {
        insertRow("oc-1", "openclaw", "http://127.0.0.1:1/a", 1, 0);
        insertRow("hm-1", "hermes", "http://127.0.0.1:1/b", 1, 0);
        insertRow("eng-1", "engine", null, 1, 0);
        insertRow("oc-off", "openclaw", "http://127.0.0.1:1/c", 0, 0);
        insertRow("hm-del", "hermes", "http://127.0.0.1:1/d", 1, 1);
    }

    private void insertRow(String hostId, String hostType, String healthUrl, int status, int delFlag) {
        WsHostWhitelist row = new WsHostWhitelist();
        row.setHostId(hostId);
        row.setHostName(hostId);
        row.setOwnerName("t");
        row.setIsTeam(0);
        row.setStatus(status);
        row.setDelFlag(delFlag);
        row.setHostType(hostType);
        row.setHealthCheckUrl(healthUrl);
        row.setOnlineStatus("offline");
        row.setCreateBy("test");
        wsHostWhitelistMapper.insert(row);
    }

    @Test
    void selectHostsForHttpHealthCheck_returnsOnlyEnabledOpenclawAndHermes() {
        List<WsHostWhitelist> list = wsHostWhitelistMapper.selectHostsForHttpHealthCheck();
        List<String> ids = list.stream().map(WsHostWhitelist::getHostId).sorted().collect(Collectors.toList());
        assertThat(ids).containsExactly("hm-1", "oc-1");
    }
}
