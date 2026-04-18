package com.wx.fbsir.web.core.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.wx.fbsir.common.config.WxFbsirConfig;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Swagger2的接口配置
 * 
 * @author wxfbsir
 */
@Configuration
public class SwaggerConfig
{
    /** 系统基础配置 */
    @Autowired
    private WxFbsirConfig wxwxwxfbsirConfig;
    
    /**
     * 自定义的 OpenAPI 对象
     */
    @Bean
    public OpenAPI customOpenApi()
    {
        return new OpenAPI().components(new Components()
            // 设置认证的请求头
            .addSecuritySchemes("apikey", securityScheme()))
            .addSecurityItem(new SecurityRequirement().addList("apikey"))
            .info(getApiInfo());
    }
    
    @Bean
    public SecurityScheme securityScheme()
    {
        return new SecurityScheme()
            .type(SecurityScheme.Type.APIKEY)
            .name("Authorization")
            .in(SecurityScheme.In.HEADER)
            .scheme("Bearer");
    }
    
    /**
     * 添加摘要信息
     */
    public Info getApiInfo()
    {
        return new Info()
            .title(wxwxwxfbsirConfig.getName() + " · 开放 API 文档")
            .description(
                "REST 接口（OpenAPI 3.x）。覆盖系统管理、业务模块（含 AIGC 对话历史、草稿、输出物生成/导出/Webhook 等）。"
                    + " 实时多 AI 对话与登录检测走 WebSocket（/ws/client ↔ Engine），不在本文档逐条列出，详见项目 docs 中《WebSocket通信完整指南》。"
            )
            .contact(new Contact().name(wxwxwxfbsirConfig.getName()))
            .version(wxwxwxfbsirConfig.getVersion());
    }
}
