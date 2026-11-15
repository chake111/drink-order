package com.drink.server;

import com.drink.server.common.BusinessException;
import com.drink.server.common.FileUtil;
import com.drink.server.common.Md5Util;
import com.drink.server.common.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯工具类的单元测试，不启动 Spring 容器。
 */
class CommonUtilTest {

    @Test
    void md5MatchesKnownDigest() {
        assertEquals("e10adc3949ba59abbe56e057f20f883e", Md5Util.encrypt("123456"));
        assertEquals(32, Md5Util.encrypt("").length());
    }

    @Test
    void resultFactoriesFillCodeAndMessage() {
        assertEquals(1, Result.success().getCode());
        assertEquals("success", Result.success().getMsg());

        Result<String> withData = Result.success("payload");
        assertEquals(1, withData.getCode());
        assertEquals("payload", withData.getData());

        Result<String> withMsg = Result.success("自定义", "payload");
        assertEquals("自定义", withMsg.getMsg());
        assertEquals("payload", withMsg.getData());

        Result<String> error = Result.error("出错了");
        assertEquals(0, error.getCode());
        assertEquals("出错了", error.getMsg());
        assertNull(error.getData());
    }

    @Test
    void validateRejectsEmptyFile() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> FileUtil.validate(new MockMultipartFile("file", "a.png", "image/png", new byte[0])));
        assertEquals("上传文件不能为空", ex.getMessage());

        assertThrows(BusinessException.class, () -> FileUtil.validate(null));
    }

    @Test
    void validateRejectsOversizedFile() {
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        BusinessException ex = assertThrows(BusinessException.class,
                () -> FileUtil.validate(new MockMultipartFile("file", "big.png", "image/png", big)));
        assertEquals("文件大小不能超过2MB", ex.getMessage());
    }

    @Test
    void validateRejectsUnsupportedExtension() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> FileUtil.validate(new MockMultipartFile("file", "note.txt", "text/plain", "x".getBytes())));
        assertEquals("仅支持JPG、PNG、WEBP格式", ex.getMessage());
    }

    @Test
    void validateAcceptsAllowedExtensionIgnoringCase() {
        FileUtil.validate(new MockMultipartFile("file", "pic.PNG", "image/png", "x".getBytes()));
        FileUtil.validate(new MockMultipartFile("file", "pic.webp", "image/webp", "x".getBytes()));
    }

    @Test
    void uniqueFileNameKeepsLowercaseExtension() {
        String name = FileUtil.generateUniqueFileName("photo.JPG");
        assertTrue(name.endsWith(".jpg"), "扩展名未转小写: " + name);
        assertEquals(36 + 4, name.length(), "UUID 部分长度不对: " + name);
    }

    @Test
    void uniqueFileNameWithoutExtension() {
        String name = FileUtil.generateUniqueFileName("noext");
        assertFalse(name.contains("."), "无扩展名时不应补点号: " + name);
        assertFalse(FileUtil.generateUniqueFileName(null).contains("."));
    }

    @Test
    void saveFileCreatesParentDirectories(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("nested/deeper/upload.png");
        MockMultipartFile file = new MockMultipartFile("file", "upload.png", "image/png",
                "PNGDATA".getBytes(StandardCharsets.UTF_8));

        FileUtil.saveFile(file, target.toString());

        assertTrue(Files.exists(target), "文件未落盘");
        assertEquals("PNGDATA", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void saveFileWrapsIoFailure(@TempDir Path tempDir) throws Exception {
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> FileUtil.saveFile(
                        new MockMultipartFile("file", "a.png", "image/png", "x".getBytes()),
                        blocker.resolve("child.png").toString()));
        assertEquals("文件保存失败", ex.getMessage());
    }
}
