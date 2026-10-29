package com.fishmatrix.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fishmatrix.chat.domain.MessageScope;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EntityMappingTest {

    private static final Map<Class<?>, String> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put(ChatGroupEntity.class, "chat_group");
        TABLES.put(ChatMessageEntity.class, "chat_message");
        TABLES.put(ChatImageEntity.class, "chat_image");
        TABLES.put(ChatReadStateEntity.class, "chat_read_state");
    }

    @Test
    @DisplayName("实体与表名一一对应")
    void tablesMatchSchema() {
        TABLES.forEach((type, expected) -> {
            TableName tableName = type.getAnnotation(TableName.class);
            assertThat(tableName).as("%s 缺少 @TableName", type.getSimpleName()).isNotNull();
            assertThat(tableName.value()).isEqualTo(expected);
        });
    }

    @Test
    @DisplayName("主键类型与建表语句一致")
    void primaryKeyTypes() throws NoSuchFieldException {
        assertThat(tableId(ChatGroupEntity.class, "id").type()).isEqualTo(IdType.INPUT);
        assertThat(tableId(ChatMessageEntity.class, "seq").type()).isEqualTo(IdType.AUTO);
        assertThat(tableId(ChatImageEntity.class, "id").type()).isEqualTo(IdType.INPUT);

        // chat_read_state 是 (user_id, conversation_id) 复合主键，不能有 @TableId
        for (Field field : ChatReadStateEntity.class.getDeclaredFields()) {
            assertThat(field.isAnnotationPresent(TableId.class))
                    .as("复合主键表不应标记单个 @TableId")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("审计字段用 Instant 且标注 fill（已读位点的 updated_at 由 SQL 显式写入）")
    void auditFields() throws NoSuchFieldException {
        assertFilled(ChatGroupEntity.class, "createdAt", FieldFill.INSERT);
        assertFilled(ChatMessageEntity.class, "createdAt", FieldFill.INSERT);
        assertFilled(ChatImageEntity.class, "createdAt", FieldFill.INSERT);

        Field readStateUpdatedAt = ChatReadStateEntity.class.getDeclaredField("updatedAt");
        assertThat(readStateUpdatedAt.getType()).isEqualTo(Instant.class);
        TableField annotation = readStateUpdatedAt.getAnnotation(TableField.class);
        assertThat(annotation == null || annotation.fill() == FieldFill.DEFAULT)
                .as("chat_read_state.updated_at 由 GREATEST 更新语句显式写入")
                .isTrue();
    }

    @Test
    @DisplayName("scope 用枚举承载，写入值仍是 GROUP/DM")
    void scopeIsEnum() throws NoSuchFieldException {
        Field scope = ChatMessageEntity.class.getDeclaredField("scope");
        assertThat(scope.getType()).isEqualTo(MessageScope.class);
        assertThat(MessageScope.DM.getValue()).isEqualTo("DM");
        assertThat(MessageScope.GROUP.getValue()).isEqualTo("GROUP");
    }

    private static void assertFilled(Class<?> type, String fieldName, FieldFill expected)
            throws NoSuchFieldException {
        Field field = type.getDeclaredField(fieldName);
        assertThat(field.getType()).as("%s.%s 必须是 Instant", type.getSimpleName(), fieldName)
                .isEqualTo(Instant.class);
        TableField tableField = field.getAnnotation(TableField.class);
        assertThat(tableField).as("%s.%s 缺少 @TableField", type.getSimpleName(), fieldName)
                .isNotNull();
        assertThat(tableField.fill()).isEqualTo(expected);
    }

    private static TableId tableId(Class<?> type, String fieldName) throws NoSuchFieldException {
        TableId tableId = type.getDeclaredField(fieldName).getAnnotation(TableId.class);
        assertThat(tableId).as("%s.%s 缺少 @TableId", type.getSimpleName(), fieldName).isNotNull();
        return tableId;
    }
}
