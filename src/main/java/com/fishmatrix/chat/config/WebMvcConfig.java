package com.fishmatrix.chat.config;

import com.fishmatrix.chat.auth.ChatAuthInterceptor;
import org.springframework.context.annotation.Configuration;
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
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/chat/**")
                .excludePathPatterns("/api/v1/chat/health");
    }
}
