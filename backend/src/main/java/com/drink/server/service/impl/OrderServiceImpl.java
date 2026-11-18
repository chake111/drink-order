package com.drink.server.service.impl;

import com.drink.server.common.PageResult;
import com.drink.server.dto.OrderPageQuery;
import com.drink.server.dto.OrderSubmitDTO;
import com.drink.server.dto.UserOrderPageQuery;
import com.drink.server.entity.Order;
import com.drink.server.service.OrderService;
import com.drink.server.service.PendingOrderCounter;
import com.drink.server.service.order.OrderCommandService;
import com.drink.server.service.order.OrderQueryService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 订单服务门面：对外保持单一 {@link OrderService} 契约，
 * 内部把读模型（{@link OrderQueryService}）与写模型（{@link OrderCommandService}）分开。
 */
@Service
public class OrderServiceImpl implements OrderService {

    private final OrderQueryService orderQueryService;
    private final OrderCommandService orderCommandService;
    private final PendingOrderCounter pendingOrderCounter;

    public OrderServiceImpl(OrderQueryService orderQueryService,
                            OrderCommandService orderCommandService,
                            PendingOrderCounter pendingOrderCounter) {
        this.orderQueryService = orderQueryService;
        this.orderCommandService = orderCommandService;
        this.pendingOrderCounter = pendingOrderCounter;
    }

    @Override
    public PageResult<Order> pageQuery(OrderPageQuery query) {
        return orderQueryService.pageQuery(query);
    }

    @Override
    public Order getById(Long id) {
        return orderQueryService.getById(id);
    }

    @Override
    public void confirm(Long id) {
        orderCommandService.confirm(id);
    }

    @Override
    public void complete(Long id) {
        orderCommandService.complete(id);
    }

    @Override
    public void pickup(Long id) {
        orderCommandService.pickup(id);
    }

    @Override
    public void reject(Long id, String reason) {
        orderCommandService.reject(id, reason);
    }

    @Override
    public Map<String, Object> submit(Long userId, OrderSubmitDTO dto) {
        return orderCommandService.submit(userId, dto);
    }

    @Override
    public List<Order> listByUserId(Long userId, Integer status) {
        return orderQueryService.listByUserId(userId, status);
    }

    @Override
    public PageResult<Order> pageByUserId(Long userId, UserOrderPageQuery query) {
        return orderQueryService.pageByUserId(userId, query);
    }

    @Override
    public void cancel(Long userId, Long orderId) {
        orderCommandService.cancel(userId, orderId);
    }

    @Override
    public long getPendingCount() {
        return pendingOrderCounter.get();
    }
}