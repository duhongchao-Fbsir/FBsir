-- Hermes 纳管联通验证示例（已在本机执行成功则勿重复插入，host_id 唯一）
-- 健康检查 URL 使用公网可访问且稳定返回 200 的地址，便于定时任务将 online_status 更新为 online

SET NAMES utf8mb4;

INSERT INTO `ws_host_whitelist` (
  `host_id`, `host_name`, `owner_name`, `owner_contact`, `is_team`, `team_name`,
  `allowed_ips`, `status`, `expire_time`, `remark`, `del_flag`, `create_by`,
  `host_type`, `health_check_url`, `online_status`
) VALUES (
  'hermes-verify-20260414', 'Hermes纳管联通验证', 'admin', NULL, 0, NULL,
  NULL, 1, NULL, 'HTTP 健康检查验证示例（可删）', 0, 'admin',
  'hermes', 'http://127.0.0.1:8642/health', 'offline'
);
-- 需先启动 Hermes Gateway 且启用 API Server：API_SERVER_ENABLED=true，默认端口 8642，路径 /health
