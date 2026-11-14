package com.drink.server.service.impl;

import com.drink.server.common.BusinessException;
import com.drink.server.common.PageResult;
import com.drink.server.common.RedisUtil;
import com.drink.server.dto.OrderPageQuery;
import com.drink.server.dto.OrderSubmitDTO;
import com.drink.server.entity.Drink;
import com.drink.server.entity.Order;
import com.drink.server.entity.OrderDetail;
import com.drink.server.mapper.DrinkMapper;
import com.drink.server.mapper.OrderDetailMapper;
import com.drink.server.mapper.OrderMapper;
import com.drink.server.service.OrderService;
import com.drink.server.config.OrderWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    /** 订单号/取餐号共用当日序列的 key 前缀 */
    private static final String ORDER_SEQ_KEY_PREFIX = "order:seq:";

    /** 序列 key 过期时间（小时）：2 天，防止 key 无限增长 */
    private static final long ORDER_SEQ_TTL_HOURS = 48;

    private final OrderMapper orderMapper;
    private final OrderDetailMapper orderDetailMapper;
    private final DrinkMapper drinkMapper;
    private final OrderWebSocketHandler webSocketHandler;
    private final RedisUtil redisUtil;

    public OrderServiceImpl(OrderMapper orderMapper, OrderDetailMapper orderDetailMapper,
                            DrinkMapper drinkMapper, OrderWebSocketHandler webSocketHandler,
                            RedisUtil redisUtil) {
        this.orderMapper = orderMapper;
        this.orderDetailMapper = orderDetailMapper;
        this.drinkMapper = drinkMapper;
        this.webSocketHandler = webSocketHandler;
        this.redisUtil = redisUtil;
    }

    @Override
    public PageResult<Order> pageQuery(OrderPageQuery query) {
        long total = orderMapper.count(query.getStatus(), query.getOrderNo());
        List<Order> records = new ArrayList<>();
        if (total > 0) {
            records = orderMapper.pageQuery(query.getStatus(), query.getOrderNo(),
                    query.getOffset(), query.getPageSize());
            for (Order order : records) {
                order.setDetails(orderDetailMapper.listByOrderId(order.getId()));
            }
        }
        return new PageResult<>(total, records);
    }

    @Override
    public Order getById(Long id) {
        Order order = orderMapper.getById(id);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        order.setDetails(orderDetailMapper.listByOrderId(id));
        return order;
    }

    @Override
    public void confirm(Long id) {
        Order order = orderMapper.getById(id);
        if (order == null || order.getStatus() != 1) {
            throw new BusinessException("订单状态异常");
        }
        orderMapper.updateStatus(id, 2);
        broadcast();
    }

    @Override
    public void complete(Long id) {
        Order order = orderMapper.getById(id);
        if (order == null || order.getStatus() != 2) {
            throw new BusinessException("订单状态异常");
        }
        orderMapper.updateStatus(id, 3);
    }

    @Override
    public void pickup(Long id) {
        Order order = orderMapper.getById(id);
        if (order == null || order.getStatus() != 3) {
            throw new BusinessException("订单状态异常");
        }
        orderMapper.updateStatus(id, 4);
    }

    @Override
    public void reject(Long id, String reason) {
        Order order = orderMapper.getById(id);
        if (order == null || order.getStatus() != 1) {
            throw new BusinessException("订单状态异常");
        }
        orderMapper.reject(id, 5, reason);
        broadcast();
    }

    @Override
    @Transactional
    public Map<String, Object> submit(Long userId, OrderSubmitDTO dto) {
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw new BusinessException("购物车不能为空");
        }

        // 计算金额并构建明细
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderDetail> details = new ArrayList<>();
        for (OrderSubmitDTO.OrderItem item : dto.getItems()) {
            Drink drink = drinkMapper.getById(item.getDrinkId());
            if (drink == null || drink.getStatus() != 1) {
                throw new BusinessException("饮品[" + item.getDrinkId() + "]不存在或已停售");
            }
            OrderDetail detail = new OrderDetail();
            detail.setDrinkId(drink.getId());
            detail.setDrinkName(drink.getName());
            detail.setImage(drink.getImage());
            detail.setSpec(item.getSpec());
            detail.setQuantity(item.getQuantity());
            detail.setPrice(drink.getPrice());
            details.add(detail);
            totalAmount = totalAmount.add(drink.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        // 生成订单号与取餐号（共用当日序列，保证取餐号同一营业日内不重号）
        long seq = nextDailySeq();
        String orderNo = buildOrderNo(seq);
        String pickupNo = buildPickupNo(seq);

        // 创建订单
        Order order = new Order();
        order.setUserId(userId);
        order.setOrderNo(orderNo);
        order.setAmount(totalAmount);
        order.setStatus(1);
        order.setPickupType(dto.getPickupType());
        order.setPickupNo(pickupNo);
        insertWithRetry(order);
        orderNo = order.getOrderNo();

        // 保存明细
        for (OrderDetail d : details) {
            d.setOrderId(order.getId());
        }
        orderDetailMapper.insertBatch(details);

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", orderNo);
        result.put("amount", totalAmount);
        broadcast();
        return result;
    }

    @Override
    public List<Order> listByUserId(Long userId, Integer status) {
        List<Order> orders = orderMapper.listByUserId(userId, status);
        for (Order order : orders) {
            order.setDetails(orderDetailMapper.listByOrderId(order.getId()));
        }
        return orders;
    }

    @Override
    public void cancel(Long userId, Long orderId) {
        Order order = orderMapper.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作此订单");
        }
        if (order.getStatus() != 1) {
            throw new BusinessException("仅待接单状态可取消");
        }
        orderMapper.updateStatus(orderId, 5);
        broadcast();
    }

    @Override
    public long getPendingCount() {
        return orderMapper.countByStatus(1);
    }

    /**
     * 广播待接单数量给管理端
     */
    private void broadcast() {
        try {
            long count = orderMapper.countByStatus(1);
            webSocketHandler.broadcastPendingCount(count);
        } catch (Exception e) {
            log.warn("广播待接单数量失败: {}", e.getMessage());
        }
    }

    /**
     * 取当日序列号：Redis 原子自增（INCR + 首次 EXPIRE 同一脚本）。
     * Redis 不可用时返回 -1，调用方降级为随机后缀，保证下单不被 Redis 单点阻断。
     */
    private long nextDailySeq() {
        String key = ORDER_SEQ_KEY_PREFIX + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            return redisUtil.incrementWithTtl(key, ORDER_SEQ_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis 序列不可用，订单号降级为随机后缀: key={}, cause={}", key, e.getMessage());
            return -1L;
        }
    }

    /**
     * 生成订单号：年月日时分秒(14) + 当日序列(6位，溢出自然变长) = 20 字符，≤32
     */
    private String buildOrderNo(long seq) {
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        if (seq <= 0) {
            return dateTime + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        }
        return dateTime + String.format("%06d", seq);
    }

    /**
     * 生成取餐号：当日序列 %03d，超过 999 自然进位为 4 位，≤8
     */
    private String buildPickupNo(long seq) {
        if (seq <= 0) {
            char letter = (char) ('A' + ThreadLocalRandom.current().nextInt(26));
            return letter + String.format("%02d", ThreadLocalRandom.current().nextInt(100));
        }
        return String.format("%03d", seq);
    }

    /**
     * 落库订单：uk_order_no 冲突（唯一索引兜底）时换号重试 1 次，仍失败抛业务异常
     */
    private void insertWithRetry(Order order) {
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            log.warn("订单号冲突，换号重试一次, orderNo={}", order.getOrderNo());
            order.setId(null);
            long seq = nextDailySeq();
            order.setOrderNo(buildOrderNo(seq));
            order.setPickupNo(buildPickupNo(seq));
            try {
                orderMapper.insert(order);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException("下单失败，请重试");
            }
        }
    }
}
