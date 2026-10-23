package com.fishmatrix.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fishmatrix.chat.model.ChatIdentity;
import com.fishmatrix.common.auth.CurrentUser;
import com.fishmatrix.common.auth.UserContext;
import com.fishmatrix.common.error.ApiException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ChatAuthInterceptorTest {

    private final ChatAuthInterceptor interceptor = new ChatAuthInterceptor();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void adaptsCurrentUserToChatIdentity() {
        UserContext.set(new CurrentUser(3, "jia", "甲", "USER", List.of("USER"), Set.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        assertThat(request.getAttribute(ChatAuthInterceptor.ATTR_IDENTITY))
                .isEqualTo(ChatIdentity.of(3, "甲", "USER"));
    }

    @Test
    void rejectsRequestWithoutCurrentUser() {
        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("登录");
    }
}
