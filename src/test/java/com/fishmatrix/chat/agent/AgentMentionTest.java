package com.fishmatrix.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentMentionTest {

    @Test
    @DisplayName("标准写法：@名字 后面跟提问")
    void parsesLeadingMention() {
        var mention = AgentMention.parse("@小鱼 帮我看下 dev-group 最近的报错", "小鱼");

        assertThat(mention).isPresent();
        assertThat(mention.get().prompt()).isEqualTo("帮我看下 dev-group 最近的报错");
        assertThat(mention.get().name()).isEqualTo("小鱼");
    }

    @Test
    @DisplayName("名字带空格时按整串匹配")
    void parsesNameWithSpace() {
        var mention = AgentMention.parse("@FishMatrix Agent 发布流程是什么", "FishMatrix Agent");

        assertThat(mention).isPresent();
        assertThat(mention.get().prompt()).isEqualTo("发布流程是什么");
    }

    @Test
    @DisplayName("英文名字大小写不敏感；只 @ 不提问时给一句默认问候")
    void ignoresCaseAndFallsBackToGreeting() {
        var mention = AgentMention.parse("@fishmatrix agent", "FishMatrix Agent");

        assertThat(mention).isPresent();
        assertThat(mention.get().prompt()).isEqualTo("你好");
    }

    @Test
    @DisplayName("中文不加空格也能触发")
    void parsesWithoutSpaceBeforeQuestion() {
        var mention = AgentMention.parse("@小鱼，今天发布了吗", "小鱼");

        assertThat(mention).isPresent();
        assertThat(mention.get().prompt()).isEqualTo("今天发布了吗");
    }

    @Test
    @DisplayName("名字后面紧跟其他字符不算同一个人")
    void rejectsLongerName() {
        assertThat(AgentMention.parse("@小鱼苗 在吗", "小鱼")).isEmpty();
    }

    @Test
    @DisplayName("邮箱、代码注解里的 @ 不触发")
    void rejectsEmailAndAnnotations() {
        assertThat(AgentMention.parse("有问题发 dev@小鱼.com", "小鱼")).isEmpty();
        assertThat(AgentMention.parse("@Override 这个写法对吗", "小鱼")).isEmpty();
    }

    @Test
    @DisplayName("一句话里出现多次 @ 只取第一处")
    void takesFirstMention() {
        var mention = AgentMention.parse("@小鱼 先看这个，@小鱼 再看那个", "小鱼");

        assertThat(mention).isPresent();
        assertThat(mention.get().prompt()).isEqualTo("先看这个，@小鱼 再看那个");
    }

    @Test
    @DisplayName("空消息、空名字都不触发")
    void rejectsEmptyInput() {
        assertThat(AgentMention.parse(null, "小鱼")).isEmpty();
        assertThat(AgentMention.parse("   ", "小鱼")).isEmpty();
        assertThat(AgentMention.parse("@小鱼 在吗", "  ")).isEmpty();
        assertThat(AgentMention.parse("@小鱼 在吗", null)).isEmpty();
    }
}
