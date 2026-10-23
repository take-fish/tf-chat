package com.fishmatrix.chat.config;

import com.fishmatrix.chat.auth.ChatAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ChatAuthInterceptor authInterceptor;

    public WebMvcConfig(ChatAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 必须晚于 tf-common 的 AuthInterceptor，此时 UserContext 已写入。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/chat/**")
                .excludePathPatterns("/api/v1/chat/health")
                .order(Ordered.HIGHEST_PRECEDENCE + 10);
    }
}
