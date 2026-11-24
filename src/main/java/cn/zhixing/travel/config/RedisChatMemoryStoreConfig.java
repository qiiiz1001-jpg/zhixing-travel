package cn.zhixing.travel.config;

import cn.zhixing.travel.memory.PlanningHistoryRecovery;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageJsonCodec;
import dev.langchain4j.data.message.JacksonChatMessageJsonCodec;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import lombok.Data;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@Configuration
@Data
public class RedisChatMemoryStoreConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password}")
    private String password;

    @Value("${spring.data.redis.timeout}")
    private long timeoutMillis;

    @Value("${memory.recent.ttl-seconds:604800}")
    private long memoryTtlSeconds;

    @Value("${memory.recent.max-message-chars:6000}")
    private int maxMessageChars;

    @Bean
    public JedisPooled jedisPooled() {
        HostAndPort hostAndPort = new HostAndPort(host, port);
        DefaultJedisClientConfig.Builder configBuilder =
                DefaultJedisClientConfig.builder()
                        .user("default")
                        .connectionTimeoutMillis(Math.toIntExact(timeoutMillis))
                        .socketTimeoutMillis(Math.toIntExact(timeoutMillis));
        if (password != null && !password.isBlank()) {
            configBuilder.password(password);
        }
        return new JedisPooled(
                hostAndPort, configBuilder.build(), new GenericObjectPoolConfig<Connection>());
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public ChatMemoryStore chatMemoryStore(
            JedisPooled jedisPooled, PlanningHistoryRecovery recovery) {
        return new JedisChatMemoryStore(
                jedisPooled, memoryTtlSeconds, maxMessageChars, recovery::recover);
    }

    static class JedisChatMemoryStore implements ChatMemoryStore {

        private final JedisPooled jedisPooled;
        private final long ttlSeconds;
        private final int maxMessageChars;
        private final Function<Object, List<ChatMessage>> recovery;
        private final ChatMessageJsonCodec chatMessageJsonCodec = new JacksonChatMessageJsonCodec();

        JedisChatMemoryStore(JedisPooled jedisPooled, long ttlSeconds, int maxMessageChars) {
            this(jedisPooled, ttlSeconds, maxMessageChars, null);
        }

        JedisChatMemoryStore(
                JedisPooled jedisPooled,
                long ttlSeconds,
                int maxMessageChars,
                Function<Object, List<ChatMessage>> recovery) {
            this.jedisPooled = jedisPooled;
            this.ttlSeconds = ttlSeconds;
            this.maxMessageChars = maxMessageChars;
            this.recovery = recovery;
        }

        @Override
        public List<dev.langchain4j.data.message.ChatMessage> getMessages(Object memoryId) {
            String json;
            try {
                json = jedisPooled.get(key(memoryId));
            } catch (Exception e) {
                return Collections.emptyList();
            }
            if (json == null || json.isBlank()) {
                return restore(memoryId);
            }
            try {
                return normalizeMessages(
                        chatMessageJsonCodec.messagesFromJson(json),
                        String.valueOf(memoryId).contains("travel-memory|")
                                ? maxMessageChars
                                : Integer.MAX_VALUE);
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        private List<ChatMessage> restore(Object memoryId) {
            if (recovery == null) return Collections.emptyList();
            try {
                List<ChatMessage> restored =
                        normalizeMessages(recovery.apply(memoryId), maxMessageChars);
                String value = chatMessageJsonCodec.messagesToJson(restored);
                SetParams options = SetParams.setParams().nx();
                if (ttlSeconds > 0) options.ex(ttlSeconds);
                // 包括空历史也缓存一次；不能盖掉恢复期间其他调用写入的新窗口。
                if ("OK".equals(jedisPooled.set(key(memoryId), value, options))) return restored;
                String winner = jedisPooled.get(key(memoryId));
                return winner == null || winner.isBlank()
                        ? Collections.emptyList()
                        : normalizeMessages(
                                chatMessageJsonCodec.messagesFromJson(winner), maxMessageChars);
            } catch (Exception error) {
                // 回填失败不阻断续聊；本轮仍有 SQL 摘要、近期业务上下文与 State。
                return Collections.emptyList();
            }
        }

        @Override
        public void updateMessages(
                Object memoryId, List<dev.langchain4j.data.message.ChatMessage> messages) {
            String json =
                    chatMessageJsonCodec.messagesToJson(
                            normalizeMessages(
                                    messages,
                                    String.valueOf(memoryId).contains("travel-memory|")
                                            ? maxMessageChars
                                            : Integer.MAX_VALUE));
            String key = key(memoryId);
            try {
                if (ttlSeconds > 0) jedisPooled.setex(key, ttlSeconds, json);
                else jedisPooled.set(key, json);
            } catch (Exception e) {
                return;
            }
        }

        @Override
        public void deleteMessages(Object memoryId) {
            try {
                jedisPooled.del(key(memoryId));
            } catch (Exception e) {
                return;
            }
        }

        private static String key(Object memoryId) {
            return "chat_memory:" + String.valueOf(memoryId);
        }

        private static List<dev.langchain4j.data.message.ChatMessage> normalizeMessages(
                List<dev.langchain4j.data.message.ChatMessage> messages, int limit) {
            if (messages == null || messages.isEmpty()) {
                return Collections.emptyList();
            }

            ArrayList<dev.langchain4j.data.message.ChatMessage> cleaned =
                    new ArrayList<>(messages.size());
            dev.langchain4j.data.message.SystemMessage system = null;

            for (dev.langchain4j.data.message.ChatMessage message : messages) {
                if (message == null) {
                    continue;
                }
                if (message instanceof dev.langchain4j.data.message.SystemMessage rule) {
                    system = rule;
                    continue;
                }
                if (message instanceof dev.langchain4j.data.message.UserMessage user
                        && user.contents().size() == 1
                        && user.contents().get(0)
                                instanceof dev.langchain4j.data.message.TextContent text
                        && text.text().length() > limit) {
                    message =
                            dev.langchain4j.data.message.UserMessage.from(
                                    text.text().substring(0, limit));
                } else if (message instanceof dev.langchain4j.data.message.AiMessage ai
                        && ai.text() != null
                        && ai.text().length() > limit) {
                    message = ai.withText(ai.text().substring(0, limit));
                } else if (message
                                instanceof
                                dev.langchain4j.data.message.ToolExecutionResultMessage result
                        && result.text().length() > limit) {
                    message =
                            dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                                    result.id(),
                                    result.toolName(),
                                    result.text().substring(0, limit));
                }
                cleaned.add(message);
            }

            if (limit != Integer.MAX_VALUE) {
                int firstUser = 0;
                while (firstUser < cleaned.size()
                        && !(cleaned.get(firstUser)
                                instanceof dev.langchain4j.data.message.UserMessage)) {
                    firstUser++;
                }
                cleaned.subList(0, firstUser).clear();
            }
            // 回填后 SDK 才添加本次系统提示词，必须放回第一条；工具配对仍由 SDK 裁剪。
            if (system != null) cleaned.add(0, system);
            return cleaned;
        }
    }
}
