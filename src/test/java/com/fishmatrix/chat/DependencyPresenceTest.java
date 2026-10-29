package com.fishmatrix.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DependencyPresenceTest {

    @Test
    @DisplayName("MyBatis-Plus / Lombok 依赖已就位")
    void requiredLibrariesOnClasspath() {
        assertThat(loadable("com.baomidou.mybatisplus.core.mapper.BaseMapper")).isTrue();
        assertThat(loadable("com.baomidou.mybatisplus.annotation.TableName")).isTrue();
        assertThat(loadable("com.baomidou.mybatisplus.core.handlers.MetaObjectHandler")).isTrue();
        assertThat(loadable("lombok.Data")).isTrue();
    }

    private static boolean loadable(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
