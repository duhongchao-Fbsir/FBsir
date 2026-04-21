package com.wx.fbsir.engine.playwright.pool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 同一用户、同一持久化浏览器目录（如 mita / yuanbao）上的操作串行化。
 * <p>
 * 与 {@code YuanbaoController} 内自建 {@code USER_SERIAL_LOCKS} 目的一致：避免
 * 「检测登录 / 扫码 / 咨询」并发抢占同一 {@link com.wx.fbsir.engine.playwright.session.BrowserSession}
 * 导致页面导航与 DOM 判断互相干扰。
 */
public final class BrowserUserSerialLocks {

    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private BrowserUserSerialLocks() {
    }

    /**
     * @param platformKey 与 BrowserPool 中 name 一致，如 mita、yuanbao
     * @param userId      用户 ID
     */
    public static ReentrantLock lockFor(String platformKey, String userId) {
        if (userId == null || userId.isBlank()) {
            return new ReentrantLock();
        }
        String pk = (platformKey != null && !platformKey.isBlank()) ? platformKey : "default";
        return LOCKS.computeIfAbsent(pk + ":" + userId, k -> new ReentrantLock());
    }
}
