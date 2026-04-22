package com.wx.fbsir.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;

/**
 * Engine 副节点启动类
 * 
 * 独立部署的浏览器自动化服务，通过 WebSocket 与主节点通信
 * 功能：执行浏览器自动化任务、AI对话、媒体发布等
 * 
 * 【安全策略】禁止从环境变量读取配置参数，所有配置必须在 application.yml 中明确指定
 *
 * @author wxfbsir
 * @date 2025-12-15
 */
@SpringBootApplication
@EnableScheduling
public class WxFbsirEngineApplication {

    private static final Logger log = LoggerFactory.getLogger(WxFbsirEngineApplication.class);

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        
        try {
            SpringApplication app = new SpringApplication(WxFbsirEngineApplication.class);
            
            // 【安全策略】禁止从环境变量和系统属性读取配置
            app.setEnvironment(new NoEnvEnvironment());
            // 禁止未白名单的命令行覆盖（仍允许多实例所需键，见 parseCliOverrides）
            app.setAddCommandLineProperties(false);

            Map<String, Object> cliOverrides = parseCliOverrides(args);
            if (!cliOverrides.isEmpty()) {
                app.addInitializers((ApplicationContextInitializer<ConfigurableApplicationContext>) context -> {
                    ConfigurableEnvironment env = context.getEnvironment();
                    env.getPropertySources().addFirst(new MapPropertySource("engineCliOverrides", cliOverrides));
                    log.info("[Engine] 已应用启动参数覆盖: {}", cliOverrides.keySet());
                });
            }
            
            app.run(args);
            long endTime = System.currentTimeMillis();
            log.info("[Engine] 服务启动成功 - 耗时: {}ms", endTime - startTime);
        } catch (Exception e) {
            log.error("[Engine] 服务启动失败 - 错误: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * 自定义环境类 - 禁止从环境变量和系统属性读取配置
     */
    /**
     * 多实例/脚本传入的 Spring Boot 参数（--key=value）。在关闭通用命令行覆盖的前提下仍允许：
     * server.port、wxfbsir.engine.host-id、wxfbsir.engine.playwright.data-dir
     */
    private static Map<String, Object> parseCliOverrides(String[] args) {
        Map<String, Object> map = new HashMap<>();
        if (args == null) {
            return map;
        }
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--")) {
                continue;
            }
            int eq = arg.indexOf('=');
            if (eq <= 0 || eq >= arg.length() - 1) {
                continue;
            }
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            switch (key) {
                case "server.port" -> map.put("server.port", value);
                case "wxfbsir.engine.host-id" -> map.put("wxfbsir.engine.host-id", value);
                case "wxfbsir.engine.playwright.data-dir" -> map.put("wxfbsir.engine.playwright.data-dir", value);
                default -> { /* ignore */ }
            }
        }
        return map;
    }

    static class NoEnvEnvironment extends StandardEnvironment {
        @Override
        protected void customizePropertySources(org.springframework.core.env.MutablePropertySources propertySources) {
            super.customizePropertySources(propertySources);
            // 移除系统环境变量属性源
            propertySources.remove(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            // 移除系统属性属性源（如 -Dserver.port=8080）
            propertySources.remove(SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        }
    }
}
