package com.drink.server.dto;

import com.drink.server.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户端订单分页查询参数 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserOrderPageQuery extends PageQuery {
    private Integer status;
}
