package com.drink.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.nio.file.Paths;

/**
 * Web MVC 配置
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 上传目录，与 UploadController 写入的位置保持同一来源 */
    @Value("${drink.upload.path}")
    private String uploadPath;

    /**
     * 静态资源映射：/images/** -> 上传目录 + classpath 兜底。
     *
     * <p>以前这里硬编码 {@code file:upload/}（相对进程工作目录），与 {@code drink.upload.path}
     * 只是「恰好都指向 ./upload」才一致。一旦用 {@code DRINK_UPLOAD_PATH} 改到别的目录，
     * 就会变成「上传成功但访问 404」。现在显式由配置推导绝对路径。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/images/**")
                .addResourceLocations(uploadLocation(), "classpath:/static/images/");
    }

    /**
     * 把配置里的上传目录转成 Spring 资源定位串（目录必须以 / 结尾，否则相对解析会吃掉最后一段）
     */
    private String uploadLocation() {
        URI uri = Paths.get(uploadPath).toAbsolutePath().normalize().toUri();
        String location = uri.toString();
        return location.endsWith("/") ? location : location + "/";
    }

    /**
     * 跨域配置
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
