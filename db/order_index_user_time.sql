-- 用户端订单分页的覆盖索引
-- 目标查询（OrderMapper.listByUserIdPage）：
--   SELECT ... FROM `order` WHERE user_id = ? [AND status = ?] ORDER BY create_time DESC LIMIT ?, ?
--
-- 建 (user_id, create_time) 后，同一用户的行在索引里本来就按时间有序，
-- 反向扫一遍即可输出，不再需要 filesort。
-- idx_user_id 是它的最左前缀，被完全覆盖，因此在同一条 ALTER 里删除，索引总数不变。
--
-- 目标库：MySQL 8.0，单条 ALTER 原子执行，避免出现「新索引没建、旧索引已删」的中间态。

ALTER TABLE `order`
    ADD INDEX idx_user_create_time (user_id, create_time),
    DROP INDEX idx_user_id;

-- 回滚（如需撤销本文件）：
-- ALTER TABLE `order`
--     ADD INDEX idx_user_id (user_id),
--     DROP INDEX idx_user_create_time;
