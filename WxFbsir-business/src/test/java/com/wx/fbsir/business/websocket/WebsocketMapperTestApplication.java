package com.wx.fbsir.business.websocket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * MyBatis 映射层集成测试用最小 Spring Boot 入口（仅扫描 mapper，不加载 Controller/定时任务等）。
 */
@SpringBootApplication
@MapperScan("com.wx.fbsir.business.websocket.mapper")
@ComponentScan(
        basePackages = "com.wx.fbsir.business.websocket.mapper",
        useDefaultFilters = false
)
public class WebsocketMapperTestApplication {
}
