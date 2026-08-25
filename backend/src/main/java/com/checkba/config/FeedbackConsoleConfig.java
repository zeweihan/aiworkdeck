package com.checkba.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 反馈控制台（classpath:/static/feedback-console/index.html）的目录路径转发。
 *
 * <p>Spring Boot 的 welcome page 机制只作用于根路径，/feedback-console/ 这种
 * 子目录不会自动落到它的 index.html 上——优化者邮件里的直达链接
 * （/feedback-console/?fb=N）会 500。forward 保留原始请求的 query string，
 * 页面 JS 读 location.search 不受影响。
 */
@Configuration
public class FeedbackConsoleConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/feedback-console")
                .setViewName("forward:/feedback-console/index.html");
        registry.addViewController("/feedback-console/")
                .setViewName("forward:/feedback-console/index.html");
    }
}
