package com.fishmatrix.chat.repository;

import com.fishmatrix.chat.model.ChatImage;
import java.sql.Timestamp;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcChatImageRepository implements ChatImageRepository {

    private static final RowMapper<ChatImage> MAPPER = (rs, index) -> new ChatImage(
            rs.getString("id"),
            rs.getString("owner_user_id"),
            rs.getString("content_type"),
            rs.getLong("size"),
            rs.getTimestamp("created_at").getTime());

    private final JdbcTemplate jdbc;

    @Override
    public ChatImage insert(ChatImage image) {
        jdbc.update(
                "INSERT INTO chat_image (id, owner_user_id, content_type, size, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                image.id(),
                image.ownerUserId(),
                image.contentType(),
                image.size(),
                new Timestamp(image.createdAt()));
        return image;
    }

    @Override
    public ChatImage find(String id) {
        List<ChatImage> images = jdbc.query(
                "SELECT id, owner_user_id, content_type, size, created_at "
                        + "FROM chat_image WHERE id = ?",
                MAPPER,
                id);
        return images.isEmpty() ? null : images.get(0);
    }

    @Override
    public boolean isDirectParticipant(String imageId, String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE scope = 'DM' AND kind = 'image' "
                        + "AND content = ? AND (from_user_id = ? OR to_user_id = ?)",
                Integer.class,
                imageId,
                userId,
                userId);
        return count != null && count > 0;
    }
}
