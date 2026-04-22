package com.wx.fbsir.engine.capability;

import com.wx.fbsir.engine.WxFbsirEngineApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确保 QYWEIXIN_CAPABILITY_CATALOG 进入 {@link CapabilityRegistry} 并随注册报文上报 Admin，
 * 否则 Admin 的 {@code EngineRequestController} 会因 {@code session.hasCapability} 直接返回 CAPABILITY_NOT_FOUND。
 */
@SpringBootTest(classes = WxFbsirEngineApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "wxfbsir.engine.ws-url=ws://127.0.0.1:59999/engine-placeholder",
    "spring.main.lazy-initialization=true"
})
class CapabilityRegistryQyWeixinCatalogTest {

    @Autowired
    private CapabilityRegistry registry;

    @Test
    void qyweixin_capability_catalog_is_registered() {
        assertTrue(
            registry.hasHandler("QYWEIXIN_CAPABILITY_CATALOG"),
            "QYWEIXIN_CAPABILITY_CATALOG must be in registry so Admin heartbeat allow-list includes it"
        );
    }
}
