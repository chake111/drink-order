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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    /** 订单号/取餐号共用当日序列的 key 前缀 */
    private static final String ORDER_SEQ_KEY_PREFIX = "order:seq:";

    /** 序列 key 过期时间（小时）：2 天，防止 key 无限增长 */
    private static final long ORDER_SEQ_TTL_HOURS = 48;

    /** 幂等键存活时间（秒）：覆盖用户连点与客户端重试窗口 */
    private static final long IDEM_TTL_SECONDS = 3;

    /** Redis 不可用时的降级后缀：随机种子 + JVM 内单调自增（单实例内唯一，多实例靠 uk_order_no 兜底） */
    private static final AtomicLong FALLBACK_SEQ =
            new AtomicLong(ThreadLocalRandom.current().nextInt(1_000_000));

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

        // 幂等：同一用户 + 同一购物车指纹在 3s 内只放行一次，拦截连点与客户端重试
        String idemKey = buildIdemKey(userId, dto);
        if (!acquireIdemKey(idemKey)) {
            throw new BusinessException("订单已提交，请稍候");
        }
        try {
            return doSubmit(userId, dto);
        } catch (RuntimeException e) {
            releaseIdemKey(idemKey);
            throw e;
        }
    }

    /**
     * 下单主体：校验饮品、重算金额、生成订单号与取餐号、落库主表与明细
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
        long seq = nextDailySeq();
        String orderNo = buildOrderNo(seq);
        String pickupNo = buildPickupNo(seq, orderNo);

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
     * Redis 不可用时返回 -1，调用方降级为本地自增后缀，保证下单不被 Redis 单点阻断。
     */
    private long nextDailySeq() {
        String key = ORDER_SEQ_KEY_PREFIX + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            return redisUtil.incrementWithTtl(key, ORDER_SEQ_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis 序列不可用，订单号/取餐号降级为本地自增: key={}, cause={}", key, e.getMessage());
            return -1L;
        }
    }

    /**
     * 生成订单号：年月日时分秒(14) + 6 位后缀 = 20 字符，≤32。
     * 降级后缀取「随机种子 + JVM 内单调自增」而非纯随机：纯随机 200 个后缀的后 3 位去重期望仅约 181，
     * 无法保证取餐号同批不重；单调自增可保证同批 1000 单内后 3 位互不相同。
     */
    private String buildOrderNo(long seq) {
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        long suffix = seq > 0 ? seq : FALLBACK_SEQ.incrementAndGet();
        return dateTime + String.format("%06d", Math.floorMod(suffix, 1_000_000L));
    }

    /**
     * 生成取餐号：正常路径为当日序列 %03d（超过 999 自然进位为 4 位，≤8）；
     * 降级路径取订单号后 3 位，保证与订单号同源、同批不重号。
     */
    private String buildPickupNo(long seq, String orderNo) {
        if (seq > 0) {
            return String.format("%03d", seq);
        }
        return orderNo.substring(orderNo.length() - 3);
    }

    /**
     * 构造幂等键：idem:order:{userId}:{sha256}
     * 指纹按 (drinkId, spec, quantity) 排序后摘要，避免 items 顺序抖动导致幂等失效
     */
    private String buildIdemKey(Long userId, OrderSubmitDTO dto) {
        List<OrderSubmitDTO.OrderItem> sorted = new ArrayList<>(dto.getItems());
        sorted.sort(Comparator
                .comparing(OrderSubmitDTO.OrderItem::getDrinkId, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(OrderSubmitDTO.OrderItem::getSpec, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(OrderSubmitDTO.OrderItem::getQuantity, Comparator.nullsFirst(Comparator.naturalOrder())));

        StringBuilder sb = new StringBuilder();
        for (OrderSubmitDTO.OrderItem item : sorted) {
            sb.append(item.getDrinkId()).append('|')
                    .append(item.getSpec() == null ? "" : item.getSpec()).append('|')
                    .append(item.getQuantity()).append(';');
        }
        return "idem:order:" + userId + ":" + sha256Hex(sb.toString());
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BusinessException("下单失败，请重试");
        }
    }

    /**
     * 抢占幂等键。Redis 不可用时放行（fail-open）：可用性优先于幂等，
     * 此时仅剩 uk_order_no 兜底，与改造前的风险水平一致。
     */
    private boolean acquireIdemKey(String idemKey) {
        try {
            Boolean first = redisUtil.setIfAbsent(idemKey, "1", IDEM_TTL_SECONDS, TimeUnit.SECONDS);
            return !Boolean.FALSE.equals(first);
        } catch (Exception e) {
            log.warn("幂等键写入失败，本次放行: key={}, cause={}", idemKey, e.getMessage());
            return true;
        }
    }

    /**
     * 业务失败时释放幂等键，避免用户被自己的一次失败请求阻塞 3s
     */
    private void releaseIdemKey(String idemKey) {
        try {
            redisUtil.delete(idemKey);
        } catch (Exception e) {
            log.warn("幂等键释放失败，等待 TTL 自然过期: key={}, cause={}", idemKey, e.getMessage());
        }
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
            String retryOrderNo = buildOrderNo(seq);
            order.setOrderNo(retryOrderNo);
            order.setPickupNo(buildPickupNo(seq, retryOrderNo));
            try {
                orderMapper.insert(order);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException("下单失败，请重试");
            }
        }
    }
}
