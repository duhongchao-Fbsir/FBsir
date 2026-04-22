-- 修复因 JDBC 字符集会话与 utf8mb4 不一致导致已写入乱码的菜单与主机名称
-- 执行前请备份；请使用 utf8mb4 客户端执行，例如：
--   mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 wxfbsir < update_20260414_修复菜单与白名单中文乱码.sql

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 内容管理下：企业微信 Webhook、系统提示词管理（menu_id 见 sql/update_20260303_*.sql）
UPDATE sys_menu SET menu_name = '企业微信Webhook' WHERE menu_id = '151';
UPDATE sys_menu SET menu_name = '系统提示词管理' WHERE menu_id = '152';

-- 主机白名单种子数据中的主机名称与备注（与 sql 种子脚本一致）
UPDATE ws_host_whitelist SET
  host_name = '默认Engine节点',
  remark = '默认配置的Engine节点，对应application.yml中的host-id'
WHERE host_id = 'engine-001';

UPDATE ws_host_whitelist SET
  host_name = '开发测试节点1',
  remark = '开发环境测试用'
WHERE host_id = 'engine-dev-001';

UPDATE ws_host_whitelist SET
  host_name = '生产节点-运维组',
  remark = '生产环境主节点'
WHERE host_id = 'engine-prod-001';
