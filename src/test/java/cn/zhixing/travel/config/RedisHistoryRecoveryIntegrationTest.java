package cn.zhixing.travel.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.zhixing.travel.mapper.ConversationMapper;
import cn.zhixing.travel.memory.*;
import cn.zhixing.travel.memory.MemoryModels.*;
import cn.zhixing.travel.model.Conversation;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.UUID;

/** 真实 PostgreSQL 与 Redis 验证过期回填，不调用真实模型。 */
@EnabledIfEnvironmentVariable(named = "RUN_INFRA_TESTS", matches = "true")
class RedisHistoryRecoveryIntegrationTest {
    private final String session = UUID.randomUUID().toString();
    private final String owner = "recovery-test-" + UUID.randomUUID();
    private JdbcTemplate jdbc;
    private MemoryRepository repository;
    private PlanningHistoryRecovery recovery;
    private JedisPooled redis;

    @BeforeEach
    void setup() {
        String host = System.getenv().getOrDefault("PGVECTOR_HOST", "localhost");
        String port = System.getenv().getOrDefault("PGVECTOR_PORT", "15432");
        String database = System.getenv().getOrDefault("TRAVEL_DATABASE", "travel_planner_db");
        var source =
                new DriverManagerDataSource(
                        "jdbc:postgresql://" + host + ":" + port + "/" + database,
                        System.getenv().getOrDefault("PGVECTOR_USER", "chat"),
                        System.getenv("PGVECTOR_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("db/04-memory.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        repository =
                spy(
                        new MemoryRepository(
                                jdbc,
                                new ObjectMapper(),
                                new DataSourceTransactionManager(source)));
        jdbc.update(
                "INSERT INTO conversation(id,user_id,title) VALUES (?,?,'recovery test')",
                session,
                owner);
        var conversations = mock(ConversationMapper.class);
        when(conversations.selectById(session))
                .thenReturn(
                        Conversation.builder()
                                .id(session)
                                .userId(owner)
                                .isDelete((short) 0)
                                .build());
        recovery = new PlanningHistoryRecovery(conversations, repository);
        var config = new RedisChatMemoryStoreConfig();
        config.setHost(System.getenv().getOrDefault("REDIS_HOST", "localhost"));
        config.setPort(Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "16379")));
        config.setPassword(System.getenv().getOrDefault("REDIS_PASSWORD", ""));
        config.setTimeoutMillis(60000);
        redis = config.jedisPooled();
    }

    private void message(short role, String text) {
        jdbc.update(
                "INSERT INTO message(id,conversation_id,role,content) VALUES (?,?,?,?)",
                UUID.randomUUID().toString(),
                session,
                role,
                text);
    }

    private String memoryId(long version) {
        return "accommodation:" + MemoryScope.encode(session, version, 1);
    }

    @AfterEach
    void cleanup() {
        if (redis != null) {
            for (int version = 0; version <= 2; version++)
                redis.del("chat_memory:" + memoryId(version));
            redis.close();
        }
        if (jdbc != null) {
            jdbc.update("DELETE FROM message WHERE conversation_id=?", session);
            jdbc.update("DELETE FROM conversation WHERE id=?", session);
            jdbc.update(
                    "DELETE FROM store WHERE namespace=ARRAY['travel-memory','user',?]::text[]",
                    owner);
        }
    }

    @Test
    void expiredRedisRehydratesCompletedSqlHistoryOnceAndExcludesCurrentQuestion() {
        repository.saveSummary(owner, session, null, Summary.empty(0, 0, 0));
        message((short) 1, "上次想去西安，查过酒店");
        message((short) 2, "1.甲酒店；2.乙酒店");
        message((short) 1, "第二个酒店太远，换一个"); // 模拟 ChatService 已保存本轮问题。
        String id = memoryId(0);
        String key = "chat_memory:" + id;
        var store =
                new RedisChatMemoryStoreConfig.JedisChatMemoryStore(
                        redis, 604800, 6000, recovery::recover);
        store.updateMessages(id, List.of(UserMessage.from("将过期的窗口")));
        redis.expire(key, 0);
        assertNull(redis.get(key));
        var restored = store.getMessages(id);
        assertEquals(2, restored.size());
        assertTrue(((AiMessage) restored.get(1)).text().contains("乙酒店"));
        assertFalse(restored.toString().contains("换一个"));
        assertTrue(redis.ttl(key) > 604700);
        var window =
                MessageWindowChatMemory.builder()
                        .id(id)
                        .chatMemoryStore(store)
                        .maxMessages(20)
                        .build();
        window.add(SystemMessage.from("本轮明确需求优先"));
        window.add(UserMessage.from("第二个酒店太远，换一个"));
        assertInstanceOf(SystemMessage.class, window.messages().get(0));
        assertEquals(
                1,
                window.messages().stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .filter(message -> message.singleText().equals("第二个酒店太远，换一个"))
                        .count());
        verify(repository, times(1)).completedRecent(session, 0, 17);
    }

    @Test
    void forgottenPreferencesCannotReturnFromSqlWhenNewWindowIsCold() {
        repository.update(
                owner,
                0,
                UUID.randomUUID().toString(),
                List.of(new Operation("food", "SET", "不吃辣")));
        repository.saveSummary(owner, session, null, Summary.empty(1, 0, 0));
        message((short) 1, "记住以后默认不辣");
        message((short) 2, "已记住美食偏好：不吃辣");
        var old = repository.summary(owner, session);
        repository.update(
                owner,
                1,
                UUID.randomUUID().toString(),
                List.of(new Operation("food", "DELETE", "")));
        repository.saveSummary(
                owner, session, old, Summary.empty(2, 1, repository.latestSequence(session)));
        var store =
                new RedisChatMemoryStoreConfig.JedisChatMemoryStore(
                        redis, 604800, 6000, recovery::recover);
        assertTrue(store.getMessages(memoryId(2)).isEmpty());
        assertFalse(redis.get("chat_memory:" + memoryId(2)).contains("不辣"));
        assertTrue(store.getMessages(memoryId(1)).isEmpty());
    }
}
