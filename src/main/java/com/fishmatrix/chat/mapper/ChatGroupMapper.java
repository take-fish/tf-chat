package com.fishmatrix.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fishmatrix.chat.entity.ChatGroupEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatGroupMapper extends BaseMapper<ChatGroupEntity> {

    /** 按运营配置的顺序列出全部群组。 */
    List<ChatGroupEntity> selectAllOrdered();
}
