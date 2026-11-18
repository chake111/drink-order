-- T3：订单列表与看板统计的索引补充
-- 仅 ADD INDEX，不改列类型、不删索引。
-- 目标库：MySQL 8.0（drink_order）

ALTER TABLE `order`
    ADD INDEX idx_status_create_time (status, create_time),
    ADD INDEX idx_create_time (create_time);

-- 回滚（如需撤销本文件）：
-- ALTER TABLE `order` DROP INDEX idx_status_create_time, DROP INDEX idx_create_time;
