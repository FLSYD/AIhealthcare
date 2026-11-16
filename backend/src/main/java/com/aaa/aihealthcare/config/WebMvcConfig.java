package com.aaa.aihealthcare.config;

import com.aaa.aihealthcare.interceptor.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Bean;
import jakarta.servlet.MultipartConfigElement;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    // 在现有 Java 配置中设置上传上限，无需修改 application.properties。
    @Bean
    public MultipartConfigElement multipartConfigElement() {
        return new MultipartConfigElement("", 2L * 1024 * 1024, 3L * 1024 * 1024, 0);
    }

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/login");
    }
}
