package com.fishmatrix.chat.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.time.Instant;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>聊天不用 offset 分页：历史/未读全部按自增 seq 做游标，所以这里不注册分页拦截器，
 * 只注册审计字段自动填充。</p>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MetaObjectHandler auditMetaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                strictInsertFill(metaObject, "createdAt", Instant.class, Instant.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                // 聊天表的 updated_at 只在已读位点更新时显式写入，这里不做通用填充。
            }
        };
    }
}
