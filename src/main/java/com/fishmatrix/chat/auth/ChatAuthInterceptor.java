package com.fishmatrix.chat.auth;

import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.common.auth.CurrentUser;
import com.fishmatrix.common.auth.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 把 tf-common 已校验的当前用户适配为聊天身份。
 * 登录校验、签名头与 Bearer 回退统一由 tf-common 完成，聊天模块不再重复实现。
 */
@Component
public class ChatAuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_IDENTITY = "chat.identity";

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        CurrentUser user = UserContext.require();
        request.setAttribute(ATTR_IDENTITY, ChatIdentity.of(user.id(), user.nickname(), user.role()));
        return true;
    }
}
