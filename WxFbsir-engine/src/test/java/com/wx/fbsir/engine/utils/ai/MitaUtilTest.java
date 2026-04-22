package com.wx.fbsir.engine.utils.ai;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 仿真：秘塔 URL 解析（去 query / hash）。
 */
class MitaUtilTest {

    @Test
    void extractChatId_stripsQueryAndHash() {
        MitaUtil util = new MitaUtil();
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://metaso.cn/foo/bar?q=1&x=2#fragment");
        assertEquals("foo/bar", util.extractChatId(page));
    }

    @Test
    void extractChatId_nullWhenNotMetaso() {
        MitaUtil util = new MitaUtil();
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://example.com/");
        assertNull(util.extractChatId(page));
    }
}
