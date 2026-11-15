package com.drink.server;

import com.drink.server.common.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口级集成测试：走 MockMvc，把每个控制器的成功与失败分支都打到。
 *
 * <p>用 {@code @Transactional} 让每个用例结束时回滚，测试库里的种子数据不会被改动；
 * Redis 的幂等键和序列不在事务内，单独在 {@code @AfterEach} 清理。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "spring.data.redis.database=15",
        "drink.upload.path=target/api-upload/",
        "drink.upload.url-prefix=/upload/",
        "drink.pending.calibrate-interval-ms=3600000",
        "drink.ws.heartbeat-interval-ms=3600000"
})
@AutoConfigureMockMvc
@Transactional
class ApiEndpointsTest {

    private static final String USER_OPENID = "chake111";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void loginBothSides() throws Exception {
        adminToken = tokenFrom(post("/api/admin/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin\",\"password\":\"123456\"}"));
        userToken = tokenFrom(post("/api/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"" + USER_OPENID + "\"}"));
        assertFalse(adminToken.isBlank(), "管理端 token 为空");
        assertFalse(userToken.isBlank(), "用户端 token 为空");
    }

    @AfterEach
    void cleanRedisKeys() {
        for (String pattern : new String[]{"order:seq:*", "idem:order:*", "drink:category:*"}) {
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }

    @AfterEach
    void cleanUploadDir() throws Exception {
        Path dir = Path.of("target", "api-upload");
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }

    // ==================== 鉴权与异常 ====================

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        mvc.perform(get("/api/order/page"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithMalformedTokenIsRejected() throws Exception {
        mvc.perform(get("/api/order/page").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void optionsRequestBypassesAuth() throws Exception {
        mvc.perform(options("/api/order/page")).andExpect(status().isOk());
    }

    @Test
    void unknownPathReturns404() throws Exception {
        mvc.perform(get("/api/definitely-missing").header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void pathVariableTypeMismatchFallsIntoGenericHandler() throws Exception {
        JsonNode body = okJson(get("/api/order/abc").header("Authorization", bearer(adminToken)));
        assertEquals(0, body.path("code").asInt());
        assertEquals("服务器内部错误", body.path("msg").asText());
    }

    // ==================== JWT ====================

    @Test
    void jwtRoundTripAndInvalidSignature() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", 51L);
        claims.put("type", "user");

        String token = jwtUtil.generateToken(claims);
        assertTrue(jwtUtil.validateToken(token));
        assertEquals(51, jwtUtil.parseToken(token).get("id", Integer.class).intValue());
        assertFalse(jwtUtil.validateToken(token + "x"), "篡改后的 token 不应通过校验");
        assertFalse(jwtUtil.validateToken("garbage"));
    }

    // ==================== 管理端登录 ====================

    @Test
    void adminLoginRejectsWrongPassword() throws Exception {
        JsonNode body = json(post("/api/admin/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin\",\"password\":\"wrong\"}"));
        assertEquals(0, body.path("code").asInt());
        assertEquals("密码错误", body.path("msg").asText());
    }

    @Test
    void adminLoginRejectsUnknownAccount() throws Exception {
        JsonNode body = json(post("/api/admin/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"nobody\",\"password\":\"123456\"}"));
        assertEquals("账号不存在", body.path("msg").asText());
    }

    @Test
    void adminLoginRejectsDisabledAccount() throws Exception {
        String account = "disabled_" + System.nanoTime();
        okJson(post("/api/employee")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"待禁用\",\"account\":\"" + account
                        + "\",\"password\":\"123456\",\"role\":\"店员\"}"));
        Long employeeId = jdbcTemplate.queryForObject(
                "SELECT id FROM employee WHERE account = ?", Long.class, account);
        okJson(post("/api/employee/status/0?id=" + employeeId)
                .header("Authorization", bearer(adminToken)));

        JsonNode body = json(post("/api/admin/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"" + account + "\",\"password\":\"123456\"}"));
        assertEquals("账号已被禁用", body.path("msg").asText());
    }

    // ==================== 分类 ====================

    @Test
    void categoryCrudAndStatusAndSort() throws Exception {
        JsonNode list = okJson(get("/api/category/list").header("Authorization", bearer(adminToken)));
        assertTrue(list.path("data").isArray());

        JsonNode added = okJson(post("/api/category")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"集成测试分类\"}"));
        assertEquals(1, added.path("code").asInt());

        Long newId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '集成测试分类'", Long.class);
        assertNotNull(newId);

        okJson(put("/api/category")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":" + newId + ",\"name\":\"改名后的分类\"}"));

        okJson(post("/api/category/status/0?id=" + newId).header("Authorization", bearer(adminToken)));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT status FROM category WHERE id = ?", Integer.class, newId));

        okJson(put("/api/category/sort")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + newId + "]}"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT sort FROM category WHERE id = ?", Integer.class, newId));

        okJson(delete("/api/category/" + newId).header("Authorization", bearer(adminToken)));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category WHERE id = ?", Integer.class, newId));
    }

    @Test
    void categoryDeleteBlockedWhenDrinksExist() throws Exception {
        Long categoryWithDrinks = jdbcTemplate.queryForObject(
                "SELECT category_id FROM drink GROUP BY category_id HAVING COUNT(*) > 0 LIMIT 1", Long.class);

        JsonNode body = okJson(delete("/api/category/" + categoryWithDrinks)
                .header("Authorization", bearer(adminToken)));
        assertEquals(0, body.path("code").asInt());
        assertTrue(body.path("msg").asText().contains("不允许删除"));
    }

    // ==================== 饮品 ====================

    @Test
    void drinkPageAndDetail() throws Exception {
        JsonNode page = okJson(get("/api/drink/page?page=1&pageSize=5")
                .header("Authorization", bearer(adminToken)));
        assertTrue(page.path("data").path("total").asLong() > 0);
        assertTrue(page.path("data").path("records").size() <= 5);

        Long anyDrinkId = jdbcTemplate.queryForObject("SELECT id FROM drink LIMIT 1", Long.class);
        JsonNode detail = okJson(get("/api/drink/" + anyDrinkId).header("Authorization", bearer(adminToken)));
        assertEquals(anyDrinkId.longValue(), detail.path("data").path("id").asLong());

        JsonNode missing = okJson(get("/api/drink/999999").header("Authorization", bearer(adminToken)));
        assertEquals("饮品不存在", missing.path("msg").asText());
    }

    @Test
    void drinkAddUpdateStatusDelete() throws Exception {
        Long categoryId = jdbcTemplate.queryForObject("SELECT id FROM category LIMIT 1", Long.class);

        okJson(post("/api/drink")
                .param("name", "集成测试饮品")
                .param("categoryId", String.valueOf(categoryId))
                .param("price", "18.50")
                .param("image", "/upload/it.png")
                .param("flavors", "[{\"name\":\"甜度\",\"value\":\"半糖\"}]")
                .header("Authorization", bearer(adminToken)));

        Long drinkId = jdbcTemplate.queryForObject(
                "SELECT id FROM drink WHERE name = '集成测试饮品'", Long.class);
        assertNotNull(drinkId);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM drink_flavor WHERE drink_id = ?", Integer.class, drinkId));

        okJson(put("/api/drink")
                .param("id", String.valueOf(drinkId))
                .param("name", "改名饮品")
                .param("categoryId", String.valueOf(categoryId))
                .param("price", "20.00")
                .param("image", "/upload/it.png")
                .param("flavors", "[]")
                .header("Authorization", bearer(adminToken)));

        okJson(post("/api/drink/status/0?ids=" + drinkId).header("Authorization", bearer(adminToken)));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT status FROM drink WHERE id = ?", Integer.class, drinkId));

        okJson(delete("/api/drink?ids=" + drinkId).header("Authorization", bearer(adminToken)));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM drink WHERE id = ?", Integer.class, drinkId));
    }

    @Test
    void drinkRejectsMalformedFlavors() throws Exception {
        Long categoryId = jdbcTemplate.queryForObject("SELECT id FROM category LIMIT 1", Long.class);

        JsonNode body = okJson(post("/api/drink")
                .param("name", "坏口味饮品")
                .param("categoryId", String.valueOf(categoryId))
                .param("price", "10.00")
                .param("image", "/upload/it.png")
                .param("flavors", "not-a-json")
                .header("Authorization", bearer(adminToken)));

        assertEquals(0, body.path("code").asInt());
        assertEquals("口味数据格式错误", body.path("msg").asText());
    }

    @Test
    void drinkUpdateEdgeCasesAndCacheFallback() throws Exception {
        Long categoryId = jdbcTemplate.queryForObject("SELECT id FROM category LIMIT 1", Long.class);
        Long otherCategory = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE id <> ? LIMIT 1", Long.class, categoryId);

        JsonNode missing = okJson(put("/api/drink")
                .param("id", "999999")
                .param("name", "不存在")
                .param("categoryId", String.valueOf(categoryId))
                .param("price", "10.00")
                .param("image", "/upload/x.png")
                .header("Authorization", bearer(adminToken)));
        assertEquals("饮品不存在", missing.path("msg").asText());

        okJson(post("/api/drink")
                .param("name", "换分类饮品")
                .param("categoryId", String.valueOf(categoryId))
                .param("price", "16.00")
                .param("image", "/upload/x.png")
                .header("Authorization", bearer(adminToken)));

        Long drinkId = jdbcTemplate.queryForObject(
                "SELECT id FROM drink WHERE name = '换分类饮品'", Long.class);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM drink_flavor WHERE drink_id = ?", Integer.class, drinkId),
                "未传 flavors 时不应写入口味");

        okJson(put("/api/drink")
                .param("id", String.valueOf(drinkId))
                .param("name", "换分类饮品")
                .param("categoryId", String.valueOf(otherCategory))
                .param("price", "16.00")
                .param("image", "/upload/x.png")
                .param("flavors", "[]")
                .header("Authorization", bearer(adminToken)));
        assertEquals(otherCategory, jdbcTemplate.queryForObject(
                "SELECT category_id FROM drink WHERE id = ?", Long.class, drinkId));

        redisTemplate.opsForValue().set("drink:category:" + categoryId, "not-a-json");
        JsonNode fallback = json(get("/api/user/drink/list?categoryId=" + categoryId)
                .header("Authorization", bearer(userToken)));
        assertEquals(1, fallback.path("code").asInt());
        assertTrue(fallback.path("data").isArray());
    }

    // ==================== 员工 ====================

    @Test
    void employeeCrudAndGuards() throws Exception {
        JsonNode page = okJson(get("/api/employee/page?page=1&pageSize=5")
                .header("Authorization", bearer(adminToken)));
        assertTrue(page.path("data").path("total").asLong() > 0);

        okJson(post("/api/employee")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"测试员工\",\"account\":\"it_emp_1\",\"password\":\"123456\",\"role\":\"店员\"}"));

        Long empId = jdbcTemplate.queryForObject(
                "SELECT id FROM employee WHERE account = 'it_emp_1'", Long.class);
        assertNotNull(empId);

        JsonNode duplicate = okJson(post("/api/employee")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"重复账号\",\"account\":\"it_emp_1\",\"password\":\"123456\",\"role\":\"店员\"}"));
        assertEquals("账号已存在", duplicate.path("msg").asText());

        okJson(put("/api/employee")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":" + empId + ",\"name\":\"测试员工改\",\"account\":\"it_emp_1\",\"password\":\"654321\",\"role\":\"店员\"}"));

        okJson(post("/api/employee/status/0?id=" + empId).header("Authorization", bearer(adminToken)));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT status FROM employee WHERE id = ?", Integer.class, empId));

        okJson(delete("/api/employee/" + empId).header("Authorization", bearer(adminToken)));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE id = ?", Integer.class, empId));
    }

    @Test
    void employeeDeleteGuards() throws Exception {
        Long managerId = jdbcTemplate.queryForObject(
                "SELECT id FROM employee WHERE role = '店长' LIMIT 1", Long.class);
        JsonNode manager = okJson(delete("/api/employee/" + managerId)
                .header("Authorization", bearer(adminToken)));
        assertEquals("岗位为店长的员工不可删除", manager.path("msg").asText());

        JsonNode missing = okJson(delete("/api/employee/999999")
                .header("Authorization", bearer(adminToken)));
        assertEquals("员工不存在", missing.path("msg").asText());
    }

    // ==================== 用户端基础接口 ====================

    @Test
    void userLoginRegistersNewOpenid() throws Exception {
        String openid = "it_user_" + System.currentTimeMillis();
        JsonNode body = json(post("/api/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"" + openid + "\"}"));

        assertEquals(1, body.path("code").asInt());
        String expectedNickname = openid.substring(0, Math.min(20, openid.length()));
        assertEquals(expectedNickname, body.path("data").path("nickname").asText());
        assertFalse(body.path("data").path("token").asText().isBlank());
    }

    @Test
    void userCategoryAndDrinkBrowsing() throws Exception {
        JsonNode categories = json(get("/api/user/category/list")
                .header("Authorization", bearer(userToken)));
        assertTrue(categories.path("data").isArray());
        categories.path("data").forEach(c -> assertEquals(1, c.path("status").asInt()));

        Long categoryId = jdbcTemplate.queryForObject("SELECT id FROM category LIMIT 1", Long.class);
        JsonNode drinks = json(get("/api/user/drink/list?categoryId=" + categoryId)
                .header("Authorization", bearer(userToken)));
        assertTrue(drinks.path("data").isArray());

        Long drinkId = jdbcTemplate.queryForObject("SELECT id FROM drink LIMIT 1", Long.class);
        JsonNode detail = json(get("/api/user/drink/" + drinkId)
                .header("Authorization", bearer(userToken)));
        assertEquals(drinkId.longValue(), detail.path("data").path("id").asLong());
    }

    // ==================== 订单：管理端 ====================

    @Test
    void adminOrderPageDetailAndPendingCount() throws Exception {
        JsonNode page = okJson(get("/api/order/page?page=1&pageSize=5")
                .header("Authorization", bearer(adminToken)));
        assertTrue(page.path("data").path("total").asLong() > 0);

        Long orderId = jdbcTemplate.queryForObject("SELECT id FROM `order` LIMIT 1", Long.class);
        JsonNode detail = okJson(get("/api/order/" + orderId).header("Authorization", bearer(adminToken)));
        assertEquals(orderId.longValue(), detail.path("data").path("id").asLong());

        JsonNode missing = okJson(get("/api/order/99999999").header("Authorization", bearer(adminToken)));
        assertEquals("订单不存在", missing.path("msg").asText());

        // 计数存在内存里、只在真正提交后变动；本类用 @Transactional 回滚，事务内不会触发
        // afterCommit，因此这里只校验接口形态，计数增减由 PendingCountTest（非事务）覆盖
        JsonNode pending = okJson(get("/api/order/pending-count").header("Authorization", bearer(adminToken)));
        assertTrue(pending.path("data").isNumber(), "pending-count 应返回数值");
    }

    @Test
    void adminOrderStateTransitionsAndConflicts() throws Exception {
        Long pendingOrder = jdbcTemplate.queryForObject(
                "SELECT id FROM `order` WHERE status = 1 LIMIT 1", Long.class);

        okJson(post("/api/order/confirm/" + pendingOrder).header("Authorization", bearer(adminToken)));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT status FROM `order` WHERE id = ?", Integer.class, pendingOrder));

        JsonNode conflict = okJson(post("/api/order/confirm/" + pendingOrder)
                .header("Authorization", bearer(adminToken)));
        assertEquals("订单状态异常", conflict.path("msg").asText());

        okJson(post("/api/order/complete/" + pendingOrder).header("Authorization", bearer(adminToken)));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT status FROM `order` WHERE id = ?", Integer.class, pendingOrder));

        okJson(post("/api/order/pickup/" + pendingOrder).header("Authorization", bearer(adminToken)));
        assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT status FROM `order` WHERE id = ?", Integer.class, pendingOrder));

        Long rejectTarget = jdbcTemplate.queryForObject(
                "SELECT id FROM `order` WHERE status = 1 LIMIT 1", Long.class);
        okJson(post("/api/order/reject/" + rejectTarget)
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rejectReason\":\"测试拒单\"}"));
        assertEquals(5, jdbcTemplate.queryForObject(
                "SELECT status FROM `order` WHERE id = ?", Integer.class, rejectTarget));
    }

    // ==================== 订单：用户端 ====================

    @Test
    void userSubmitListPageAndCancel() throws Exception {
        String spec = "it-" + System.nanoTime();
        JsonNode submitted = json(post("/api/user/order")
                .header("Authorization", bearer(userToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitBody(spec)));
        assertEquals(1, submitted.path("code").asInt(), submitted.toString());
        String orderNo = submitted.path("data").path("orderNo").asText();
        assertEquals(20, orderNo.length());

        Long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM `order` WHERE order_no = ?", Long.class, orderNo);

        JsonNode list = json(get("/api/user/order/list").header("Authorization", bearer(userToken)));
        assertTrue(list.path("data").size() >= 1);

        int expectedTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` o JOIN user u ON o.user_id = u.id WHERE u.openid = ?",
                Integer.class, USER_OPENID);
        JsonNode page = json(get("/api/user/order/page?page=1&pageSize=2")
                .header("Authorization", bearer(userToken)));
        assertEquals(1, page.path("code").asInt());
        assertEquals(expectedTotal, page.path("data").path("total").asInt(), "total 应等于该用户订单总数");
        assertEquals(2, page.path("data").path("records").size(), "首页应恰好返回 pageSize 条");

        okJson(post("/api/user/order/cancel/" + orderId).header("Authorization", bearer(userToken)));
        assertEquals(5, jdbcTemplate.queryForObject(
                "SELECT status FROM `order` WHERE id = ?", Integer.class, orderId));
    }

    @Test
    void userSubmitRejectsEmptyCartAndUnknownDrink() throws Exception {
        JsonNode empty = json(post("/api/user/order")
                .header("Authorization", bearer(userToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pickupType\":\"自取\",\"items\":[]}"));
        assertEquals("购物车不能为空", empty.path("msg").asText());

        JsonNode unknown = json(post("/api/user/order")
                .header("Authorization", bearer(userToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pickupType\":\"自取\",\"items\":[{\"drinkId\":999999,\"spec\":\"x\",\"quantity\":1}]}"));
        assertTrue(unknown.path("msg").asText().contains("不存在或已停售"));
    }

    @Test
    void userCancelGuards() throws Exception {
        Long notMine = jdbcTemplate.queryForObject(
                "SELECT id FROM `order` WHERE status = 1 AND user_id <> "
                        + "(SELECT id FROM user WHERE openid = '" + USER_OPENID + "') LIMIT 1", Long.class);
        JsonNode denied = json(post("/api/user/order/cancel/" + notMine)
                .header("Authorization", bearer(userToken)));
        assertEquals("无权操作此订单", denied.path("msg").asText());

        JsonNode missing = json(post("/api/user/order/cancel/99999999")
                .header("Authorization", bearer(userToken)));
        assertEquals("订单不存在", missing.path("msg").asText());

        Long notPending = jdbcTemplate.queryForObject(
                "SELECT o.id FROM `order` o JOIN user u ON o.user_id = u.id "
                        + "WHERE u.openid = '" + USER_OPENID + "' AND o.status = 4 LIMIT 1", Long.class);
        JsonNode wrongState = json(post("/api/user/order/cancel/" + notPending)
                .header("Authorization", bearer(userToken)));
        assertEquals("仅待接单状态可取消", wrongState.path("msg").asText());
    }

    @Test
    void userOrderPageClampsHugePageNumber() throws Exception {
        JsonNode body = json(get("/api/user/order/page?page=2147483647&pageSize=50")
                .header("Authorization", bearer(userToken)));
        assertEquals(1, body.path("code").asInt(), "超大 page 不应导致 500: " + body);
        assertTrue(body.path("data").path("total").asLong() > 0);
        assertTrue(body.path("data").path("records").isEmpty());
    }

    // ==================== 看板 ====================

    @Test
    void dashboardEndpoints() throws Exception {
        JsonNode today = okJson(get("/api/dashboard/today").header("Authorization", bearer(adminToken)));
        assertTrue(today.path("data").isObject());

        JsonNode trend = okJson(get("/api/dashboard/trend").header("Authorization", bearer(adminToken)));
        assertTrue(trend.path("data").isArray());

        JsonNode top = okJson(get("/api/dashboard/top").header("Authorization", bearer(adminToken)));
        assertTrue(top.path("data").isArray());
    }

    // ==================== 上传 ====================

    @Test
    void uploadSavesAllowedImage() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cup.png", "image/png", "PNGDATA".getBytes(StandardCharsets.UTF_8));

        JsonNode body = json(multipart("/api/upload")
                .file(file)
                .header("Authorization", bearer(adminToken)));

        assertEquals(1, body.path("code").asInt());
        String url = body.path("data").asText();
        assertTrue(url.startsWith("/upload/"), url);
        assertTrue(Files.exists(Path.of("target", "api-upload", url.substring("/upload/".length()))));
    }

    @Test
    void uploadRejectsEmptyAndWrongType() throws Exception {
        JsonNode empty = json(multipart("/api/upload")
                .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                .header("Authorization", bearer(adminToken)));
        assertEquals("上传文件不能为空", empty.path("msg").asText());

        JsonNode wrongType = json(multipart("/api/upload")
                .file(new MockMultipartFile("file", "note.txt", "text/plain", "x".getBytes()))
                .header("Authorization", bearer(adminToken)));
        assertEquals("仅支持JPG、PNG、WEBP格式", wrongType.path("msg").asText());
    }

    // ==================== 辅助 ====================

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String submitBody(String spec) {
        return "{\"pickupType\":\"自取\",\"items\":[{\"drinkId\":1,\"spec\":\"" + spec + "\",\"quantity\":1}]}";
    }

    private String tokenFrom(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode okJson(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
