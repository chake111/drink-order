package com.drink.server.common;

import lombok.Data;

/**
 * 分页请求封装
 */
@Data
public class PageQuery {

    /** 页码，从1开始 */
    private Integer page = 1;

    /** 每页条数 */
    private Integer pageSize = 10;

    /**
     * 计算偏移量（MySQL LIMIT offset, size）。
     *
     * <p>用 long 算完再落回 int：{@code (page - 1) * pageSize} 是 int 乘法，
     * page 取到 Integer.MAX_VALUE 时（例如 ?page=2147483647&pageSize=50）会溢出成负数，
     * SQL 变成 {@code LIMIT -100, 50}，MySQL 直接报 1064 语法错，接口变成 500。
     *
     * <p>边界处理：page < 1 按第 1 页；pageSize 为 0 或负数时 offset 归 0（与原行为一致）；
     * 结果上限压到 Integer.MAX_VALUE，仍是一条合法的 LIMIT。
     */
    public int getOffset() {
        long safePage = page == null || page < 1 ? 1L : page;
        long safeSize = pageSize == null ? 10L : pageSize;
        long offset = (safePage - 1) * safeSize;
        if (offset <= 0) {
            return 0;
        }
        return (int) Math.min(offset, Integer.MAX_VALUE);
    }
}
