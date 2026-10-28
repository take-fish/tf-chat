package com.fishmatrix.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class MapperLocationTest {

    @Test
    @DisplayName("classpath:mapper/*.xml 能解析到全部手写 SQL")
    void configuredPatternResolvesXml() throws IOException {
        Resource[] resources =
                new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml");

        assertThat(resources).extracting(Resource::getFilename)
                .contains("ChatGroupMapper.xml", "ChatMessageMapper.xml",
                        "ChatReadStateMapper.xml");
    }
}
