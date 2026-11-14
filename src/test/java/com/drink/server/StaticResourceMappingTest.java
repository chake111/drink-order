package com.drink.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 静态资源映射回归：{@code /images/**} 必须跟着 {@code drink.upload.path} 走，而不是跟着进程工作目录走。
 *
 * <p>历史缺陷：{@code WebMvcConfig} 硬编码 {@code file:upload/}，本地与容器里恰好都等于
 * {@code ./upload} 所以看不出问题；一旦用 {@code DRINK_UPLOAD_PATH} 改目录，上传成功但访问报错。
 *
 * <p>同时锁住「资源不存在返回 404」——以前会被兜底异常处理器包成 200 + 「服务器内部错误」。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456",
                "spring.data.redis.database=15",
                "drink.upload.path=target/test-upload/",
                "drink.pending.calibrate-interval-ms=3600000",
                "drink.ws.heartbeat-interval-ms=3600000"
        })
class StaticResourceMappingTest {

    private static final Path UPLOAD_DIR = Path.of("target", "test-upload");

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void prepareUploadDir() throws IOException {
        Files.createDirectories(UPLOAD_DIR);
        Files.writeString(UPLOAD_DIR.resolve("probe.png"), "PNGDATA", StandardCharsets.UTF_8);
    }

    /**
     * 配置目录里的文件必须能通过 /images/** 访问到（改造前这里会被解析到 ./upload，返回错误）
     */
    @Test
    void servesFileFromConfiguredUploadPath() {
        ResponseEntity<byte[]> response = rest.getForEntity("/images/probe.png", byte[].class);
        String body = response.getBody() == null ? "" : new String(response.getBody(), StandardCharsets.UTF_8);

        System.out.println("T5 静态资源: status=" + response.getStatusCode().value()
                + ", contentType=" + response.getHeaders().getContentType() + ", body=" + body);

        assertEquals(200, response.getStatusCode().value(), "配置目录里的文件未被静态映射命中");
        assertEquals("PNGDATA", body, "返回内容与上传目录里的文件不一致");
    }

    /**
     * 文件不存在时必须是 404，不能是 200 + 「服务器内部错误」
     */
    @Test
    void missingResourceReturns404() {
        ResponseEntity<String> response = rest.getForEntity("/images/not-exists.png", String.class);
        System.out.println("T5 缺失资源: status=" + response.getStatusCode().value() + ", body=" + response.getBody());

        assertEquals(404, response.getStatusCode().value(), "缺失资源应返回 404");
    }
}
