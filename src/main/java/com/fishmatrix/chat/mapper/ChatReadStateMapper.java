package com.fishmatrix.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fishmatrix.chat.entity.ChatReadStateEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatReadStateMapper extends BaseMapper<ChatReadStateEntity> {

    Long selectLastReadSeq(@Param("userId") String userId,
            @Param("conversationId") String conversationId);

    /** 位点只增不减：并发心跳不会把已读进度改小。 */
    int updateLastReadSeq(@Param("userId") String userId,
            @Param("conversationId") String conversationId,
            @Param("seq") long seq, @Param("now") Instant now);

    int insertReadState(@Param("userId") String userId,
            @Param("conversationId") String conversationId,
            @Param("seq") long seq, @Param("now") Instant now);
}
