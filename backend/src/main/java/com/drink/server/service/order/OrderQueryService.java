package com.drink.server.service.order;

import com.drink.server.common.BusinessException;
import com.drink.server.common.PageResult;
import com.drink.server.dto.OrderPageQuery;
import com.drink.server.dto.UserOrderPageQuery;
import com.drink.server.entity.Order;
import com.drink.server.entity.OrderDetail;
import com.drink.server.mapper.OrderDetailMapper;
import com.drink.server.mapper.OrderMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单读模型：管理端/用户端的分页、详情与明细批量装配。
 */
@Service
public class OrderQueryService {

    /** 明细 IN 查询的分段大小：每批最多 500 个 orderId */
    private static final int DETAIL_BATCH_SIZE = 500;

    private final OrderMapper orderMapper;
    private final OrderDetailMapper orderDetailMapper;

    public OrderQueryService(OrderMapper orderMapper, OrderDetailMapper orderDetailMapper) {
        this.orderMapper = orderMapper;
        this.orderDetailMapper = orderDetailMapper;
    }

    public PageResult<Order> pageQuery(OrderPageQuery query) {
        long total = orderMapper.count(query.getStatus(), query.getOrderNo());
        List<Order> records = new ArrayList<>();
        if (total > 0) {
            records = orderMapper.pageQuery(query.getStatus(), query.getOrderNo(),
                    query.getOffset(), query.getPageSize());
            fillDetails(records);
        }
        return new PageResult<>(total, records);
    }

    public Order getById(Long id) {
        Order order = orderMapper.getById(id);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        order.setDetails(orderDetailMapper.listByOrderId(id));
        return order;
    }

    public List<Order> listByUserId(Long userId, Integer status) {
        List<Order> orders = orderMapper.listByUserId(userId, status);
        fillDetails(orders);
        return orders;
    }

    public PageResult<Order> pageByUserId(Long userId, UserOrderPageQuery query) {
        // 上限收口：page 过大时 (page-1)*pageSize 会 int 溢出成负 offset，MySQL 直接报语法错
        int page = query.getPage() == null || query.getPage() < 1
                ? 1 : Math.min(query.getPage(), 1_000_000);
        int pageSize = query.getPageSize() == null ? 10 : Math.min(Math.max(query.getPageSize(), 1), 50);
        query.setPage(page);
        query.setPageSize(pageSize);

        long total = orderMapper.countByUserId(userId, query.getStatus());
        if (total == 0) {
            return new PageResult<>(0L, new ArrayList<>());
        }
        List<Order> records = orderMapper.listByUserIdPage(
                userId, query.getStatus(), query.getOffset(), pageSize);
        fillDetails(records);
        return new PageResult<>(total, records);
    }

    /**
     * 批量装配订单明细：按 orderId 分段 IN 查询（每批 ≤500）后内存分组，避免逐单查询
     * 与单条 IN 列表过长导致的包体/解析压力。
     */
    private void fillDetails(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        List<Long> orderIds = new ArrayList<>(orders.size());
        for (Order order : orders) {
            orderIds.add(order.getId());
        }
        Map<Long, List<OrderDetail>> grouped = new HashMap<>();
        for (int from = 0; from < orderIds.size(); from += DETAIL_BATCH_SIZE) {
            int to = Math.min(from + DETAIL_BATCH_SIZE, orderIds.size());
            for (OrderDetail detail : orderDetailMapper.listByOrderIds(orderIds.subList(from, to))) {
                grouped.computeIfAbsent(detail.getOrderId(), k -> new ArrayList<>()).add(detail);
            }
        }
        for (Order order : orders) {
            order.setDetails(grouped.getOrDefault(order.getId(), new ArrayList<>()));
        }
    }
}