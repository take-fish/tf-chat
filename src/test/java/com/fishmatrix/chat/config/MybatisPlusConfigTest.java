package com.fishmatrix.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MybatisPlusConfigTest {

    @Autowired
    private MetaObjectHandler metaObjectHandler;

    @Test
    @DisplayName("审计填充处理器已注册")
    void handlerRegistered() {
        assertThat(metaObjectHandler).isNotNull();
    }
}
