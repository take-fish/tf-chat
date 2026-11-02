package com.fishmatrix.chat.agent;

import java.util.Locale;
import java.util.Optional;

/**
 * 找出消息里的「@展示名」并切出真正的提问。
 *
 * <p>名字完全来自 agent 侧配置，可能带空格（例如 {@code FishMatrix Agent}），
 * 所以匹配的是整串名字而不是「@ 后第一个词」。为了不误伤邮箱、代码片段里的
 * {@code @Override}，要求 {@code @} 前是行首或空白/标点，名字后是行尾、
 * 空白或标点。{@code @小鱼苗} 不会命中 {@code 小鱼}。</p>
 */
public record AgentMention(String name, String prompt) {

    private static final String DEFAULT_PROMPT = "你好";
    private static final String BOUNDARY_BEFORE = " \t\n\u3000";
    private static final String BOUNDARY_AFTER = " \t\n\u3000,.;:!?)]}<>\"'，。；：！？、）】》”’";
    private static final String LEADING_NOISE = " \t\n\u3000,.;:!?，。；：！？、）】" ;

    public static Optional<AgentMention> parse(String content, String name) {
        if (content == null || content.isBlank() || name == null || name.isBlank()) {
            return Optional.empty();
        }
        String needle = "@" + name.strip();
        String haystack = content.toLowerCase(Locale.ROOT);
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);

        int from = 0;
        while (true) {
            int at = haystack.indexOf(lowerNeedle, from);
            if (at < 0) {
                return Optional.empty();
            }
            int end = at + lowerNeedle.length();
            if (startsAtBoundary(content, at) && endsAtBoundary(content, end)) {
                String prompt = stripLeadingNoise(content.substring(0, at) + " " + content.substring(end));
                return Optional.of(new AgentMention(
                        name.strip(), prompt.isEmpty() ? DEFAULT_PROMPT : prompt));
            }
            from = at + 1;
        }
    }

    private static boolean startsAtBoundary(String content, int at) {
        return at == 0 || BOUNDARY_BEFORE.indexOf(content.charAt(at - 1)) >= 0
                || isAgglutinative(content.charAt(at - 1));
    }

    private static boolean endsAtBoundary(String content, int end) {
        return end >= content.length() || BOUNDARY_AFTER.indexOf(content.charAt(end)) >= 0;
    }

    /**
     * 中文常不加空格（「帮我问问@小鱼」），前一字符是汉字时也允许触发；
     * 但邮箱、URL 里的 @ 前是 ASCII 字母/数字/点，仍然不触发。
     */
    private static boolean isAgglutinative(char previous) {
        return previous > 0x2E7F;
    }

    private static String stripLeadingNoise(String value) {
        int start = 0;
        while (start < value.length()
                && (LEADING_NOISE.indexOf(value.charAt(start)) >= 0
                        || Character.isWhitespace(value.charAt(start)))) {
            start++;
        }
        return value.substring(start).strip();
    }
}
