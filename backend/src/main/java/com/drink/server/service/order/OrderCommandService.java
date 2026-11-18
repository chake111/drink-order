package com.drink.server.service.order;

import com.drink.server.common.BusinessException;
import com.drink.server.dto.OrderSubmitDTO;
import com.drink.server.entity.Drink;
import com.drink.server.entity.Order;
import com.drink.server.entity.OrderDetail;
import com.drink.server.mapper.DrinkMapper;
import com.drink.server.mapper.OrderDetailMapper;
import com.drink.server.mapper.OrderMapper;
import com.drink.server.service.PendingOrderCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单写模型：接单/完成/取餐/拒单/用户下单/取消，以及待接单数的提交后广播。
 */
@Slf4j
@Service
public class OrderCommandService {

    private final OrderMapper orderMapper;
    private final OrderDetailMapper orderDetailMapper;
    private final DrinkMapper drinkMapper;
    private final PendingOrderCounter pendingOrderCounter;
    private final OrderNoGenerator orderNoGenerator;
    private final OrderIdempotencyGuard idempotencyGuard;

    public OrderCommandService(OrderMapper orderMapper, OrderDetailMapper orderDetailMapper,
                               DrinkMapper drinkMapper, PendingOrderCounter pendingOrderCounter,
                               OrderNoGenerator orderNoGenerator,
                               OrderIdempotencyGuard idempotencyGuard) {
        this.orderMapper = orderMapper;
        this.orderDetailMapper = orderDetailMapper;
        this.drinkMapper = drinkMapper;
        this.pendingOrderCounter = pendingOrderCounter;
        this.orderNoGenerator = orderNoGenerator;
        this.idempotencyGuard = idempotencyGuard;
    }

    public void confirm(Long id) {
        Order order = orderMapper.getById(id);
        if (order == null || order.getStatus() != 1) {
            throw new BusinessException("订单状态异常");
        }
        // CAS：并发重复接单时只有一次能改到行，避免待接单数被扣两次
        if (orderMapper.updateStatusIfMatch(id, 1, 2) == 0) {
            throw new BusinessException("订单状态异常");
        }
        changePendingCount(-1);
    }

    public void complete(Long id) {
        Order order = orderMapper.getById(id);
        if (order == null || order.getStatus() != 2) {
            throw new BusinessException("订单状态异常");
        }
        if (orderMapper.updateStatusIfMatch(id, 2, 3) == 0) {
            throw new BusinessException("订单状态异常");
        }
    }

    public void pickup(Long id) {
        Order order = orderMapper.getById(id);
        if (order == null || order.getStatus() != 3) {
            throw new BusinessException("订单状态异常");
        }
        if (orderMapper.updateStatusIfMatch(id, 3, 4) == 0) {
            throw new BusinessException("订单状态异常");
        }
    }

    public void reject(Long id, String reason) {
        Order order = orderMapper.getById(id);
        if (order == null || order.getStatus() != 1) {
            throw new BusinessException("订单状态异常");
        }
        if (orderMapper.rejectIfMatch(id, 1, 5, reason) == 0) {
            throw new BusinessException("订单状态异常");
        }
        changePendingCount(-1);
    }

    @Transactional
    public Map<String, Object> submit(Long userId, OrderSubmitDTO dto) {
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw new BusinessException("购物车不能为空");
        }

        // 幂等：同一用户 + 同一购物车指纹在 3s 内只放行一次，拦截连点与客户端重试
        String idemKey = idempotencyGuard.buildKey(userId, dto);
        if (!idempotencyGuard.acquire(idemKey)) {
            throw new BusinessException("订单已提交，请稍候");
        }
        try {
            return doSubmit(userId, dto);
        } catch (RuntimeException e) {
            idempotencyGuard.release(idemKey);
            throw e;
        }
    }

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
        if (orderMapper.updateStatusIfMatch(orderId, 1, 5) == 0) {
            throw new BusinessException("仅待接单状态可取消");
        }
        changePendingCount(-1);
    }

    /**
     * 下单主体：校验饮品、重算金额、生成订单号与取餐号、落库主表与明细。
     */
    private Map<String, Object> doSubmit(Long userId, OrderSubmitDTO dto) {
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
        long seq = orderNoGenerator.nextDailySeq();
        String orderNo = orderNoGenerator.buildOrderNo(seq);
        String pickupNo = orderNoGenerator.buildPickupNo(seq, orderNo);

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
        changePendingCount(1);
        return result;
    }

    /**
     * 维护待接单数并在事务提交后广播。
     *
     * <p>提交前广播会让管理端看到尚未落库的状态（客户端立即回查可能扑空），
     * 因此有事务上下文时统一挂到 {@code afterCommit}；无事务上下文（confirm/reject/cancel）
     * 直接执行——这些路径单条 CAS UPDATE 本身就是原子的隐式提交。
     *
     * <p>这三条路径**故意不加** {@code @Transactional}：CAS 已保证并发下只有一次能改到行、
     * 只有赢家扣减；而「UPDATE 提交后、内存扣减前 JVM 崩溃」这一窗口，加事务只是把扣减挪到
     * afterCommit，窗口依旧存在，反而多一个事务边界。该窗口由 60s 校准回源 DB 兜底修正。
     *
     * @param delta 待接单数增量：下单 +1，接单/拒单/取消 -1
     */
    private void changePendingCount(int delta) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pendingOrderCounter.onPendingDelta(delta);
                }
            });
        } else {
            pendingOrderCounter.onPendingDelta(delta);
        }
    }

    /**
     * 落库订单：uk_order_no 冲突（唯一索引兜底）时换号重试 1 次，仍失败抛业务异常。
     */
    private void insertWithRetry(Order order) {
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            log.warn("订单号冲突，换号重试一次, orderNo={}", order.getOrderNo());
            order.setId(null);
            long seq = orderNoGenerator.nextDailySeq();
            String retryOrderNo = orderNoGenerator.buildOrderNo(seq);
            order.setOrderNo(retryOrderNo);
            order.setPickupNo(orderNoGenerator.buildPickupNo(seq, retryOrderNo));
            try {
                orderMapper.insert(order);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException("下单失败，请重试");
            }
        }
    }
}