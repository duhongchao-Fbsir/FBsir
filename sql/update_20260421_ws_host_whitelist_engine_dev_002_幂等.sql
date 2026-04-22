-- 第二套 Engine 实例（多 host-id / 多 JVM）压测用：宿主机另起进程，host-id 与 engine-dev-001 不同。
-- 执行：mysql -h127.0.0.1 -P3306 -uroot -p wxfbsir < sql/update_20260421_ws_host_whitelist_engine_dev_002_幂等.sql

-- online_status：部分库为 tinyint，勿插入 'offline' 字符串
INSERT INTO `ws_host_whitelist` (
    `host_id`, `host_name`, `owner_name`, `is_team`, `status`, `remark`,
    `del_flag`, `host_type`
) VALUES (
    'engine-dev-002',
    '开发测试节点2-多实例',
    '压测',
    0,
    1,
    '本地第二 Engine 进程，与 engine-dev-001 并存；详见 tools/start-second-engine.ps1',
    0,
    'engine'
) ON DUPLICATE KEY UPDATE
    `host_name` = VALUES(`host_name`),
    `status` = 1,
    `remark` = VALUES(`remark`),
    `del_flag` = 0;
