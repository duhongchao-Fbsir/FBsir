-- =============================================================================
-- 目的：将「仅导入旧版 wxfbsir.sql」的库升级到与当前 WxFbsir-business Mapper 一致，
--      避免出现 Unknown column: gitee_chat_id、host_type、health_check_url、online_status
--
-- 执行方式（示例）：
--   mysql -h127.0.0.1 -P3306 -uroot -p wxfbsir < sql/update_20260421_对齐wc_chat_history与ws_host_whitelist基线_幂等.sql
--
-- 特性：幂等——可重复执行；已存在同名列则跳过 ADD COLUMN
-- MySQL：8.0+（使用 INFORMATION_SCHEMA）
-- =============================================================================

SET NAMES utf8mb4;

SET @db := DATABASE();

-- wc_chat_history.gitee_chat_id（对应 update_20260129_插入giteeAI助手.sql，合并进基线后旧库仍可能缺失）
SELECT COUNT(*) INTO @c FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wc_chat_history' AND COLUMN_NAME = 'gitee_chat_id';
SET @sql := IF(@c = 0,
  'ALTER TABLE wc_chat_history ADD COLUMN `gitee_chat_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT ''Gitee AI Chat 会话ID''',
  'SELECT ''skip: wc_chat_history.gitee_chat_id'' AS migration_note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ws_host_whitelist.host_type
SELECT COUNT(*) INTO @c FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ws_host_whitelist' AND COLUMN_NAME = 'host_type';
SET @sql := IF(@c = 0,
  'ALTER TABLE ws_host_whitelist ADD COLUMN `host_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT ''engine'' COMMENT ''主机类型：engine/openclaw/hermes 等''',
  'SELECT ''skip: ws_host_whitelist.host_type'' AS migration_note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ws_host_whitelist.health_check_url
SELECT COUNT(*) INTO @c FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ws_host_whitelist' AND COLUMN_NAME = 'health_check_url';
SET @sql := IF(@c = 0,
  'ALTER TABLE ws_host_whitelist ADD COLUMN `health_check_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT ''健康检查 URL''',
  'SELECT ''skip: ws_host_whitelist.health_check_url'' AS migration_note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ws_host_whitelist.online_status（Java 实体为 String，使用 varchar；勿用 tinyint）
SELECT COUNT(*) INTO @c FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ws_host_whitelist' AND COLUMN_NAME = 'online_status';
SET @sql := IF(@c = 0,
  'ALTER TABLE ws_host_whitelist ADD COLUMN `online_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT ''offline'' COMMENT ''在线状态：online/offline 等''',
  'SELECT ''skip: ws_host_whitelist.online_status'' AS migration_note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'update_20260421: done' AS migration_note;
