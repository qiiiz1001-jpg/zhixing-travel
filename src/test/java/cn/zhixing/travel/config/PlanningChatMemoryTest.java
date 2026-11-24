package cn.zhixing.travel.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.zhixing.travel.memory.MemoryScope;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class PlanningChatMemoryTest {
    private final JedisPooled redis = mock(JedisPooled.class);
    private final Map<String, String> values = new HashMap<>();
    private final String session = UUID.randomUUID().toString();
    private final String id = "food:" + MemoryScope.encode(session, 1, 0);
    private final RedisChatMemoryStoreConfig.JedisChatMemoryStore store =
            new RedisChatMemoryStoreConfig.JedisChatMemoryStore(redis, 604800, 12);

    @BeforeEach
    void setup() {
        when(redis.get(anyString())).thenAnswer(inv -> values.get(inv.<String>getArgument(0)));
        when(redis.setex(anyString(), anyLong(), anyString()))
                .thenAnswer(
                        inv -> {
                            values.put(inv.getArgument(0), inv.getArgument(2));
                            return "OK";
                        });
    }

    @Test
    void systemAndToolPairsSurviveStorageWhileLongResultsAreBounded() {
        var request =
                ToolExecutionRequest.builder().id("tool-1").name("search").arguments("{}").build();
        store.updateMessages(
                id,
                List.of(
                        SystemMessage.from("当前明确需求优先于历史偏好"),
                        UserMessage.from("查酒店"),
                        AiMessage.from(request),
                        ToolExecutionResultMessage.from(request, "结果".repeat(100)),
                        AiMessage.from("说明".repeat(100))));
        var messages = store.getMessages(id);
        assertEquals(5, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertEquals("tool-1", ((AiMessage) messages.get(2)).toolExecutionRequests().get(0).id());
        assertEquals("tool-1", ((ToolExecutionResultMessage) messages.get(3)).id());
        assertEquals(12, ((ToolExecutionResultMessage) messages.get(3)).text().length());
        verify(redis).setex(eq("chat_memory:" + id), eq(604800L), anyString());
    }

    @Test
    void sdkWindowDoesNotLeaveOrphanToolResults() {
        var window =
                MessageWindowChatMemory.builder()
                        .id(id)
                        .chatMemoryStore(store)
                        .maxMessages(4)
                        .build();
        var request =
                ToolExecutionRequest.builder().id("tool-1").name("search").arguments("{}").build();
        window.add(SystemMessage.from("规则"));
        window.add(UserMessage.from("问题"));
        window.add(AiMessage.from(request));
        window.add(ToolExecutionResultMessage.from(request, "结果"));
        window.add(AiMessage.from("回答"));
        window.add(UserMessage.from("新问题"));
        var messages = window.messages();
        assertTrue(messages.size() <= 4);
        assertInstanceOf(SystemMessage.class, messages.get(0));
        for (ChatMessage message : messages)
            if (message instanceof ToolExecutionResultMessage tool) {
                assertTrue(
                        messages.stream()
                                .filter(AiMessage.class::isInstance)
                                .map(AiMessage.class::cast)
                                .flatMap(ai -> ai.toolExecutionRequests().stream())
                                .anyMatch(call -> call.id().equals(tool.id())));
            }
    }

    @Test
    void planningWindowDropsLeadingToolFragmentsBeforeUserHistory() {
        var request =
                ToolExecutionRequest.builder()
                        .id("incomplete")
                        .name("search")
                        .arguments("{}")
                        .build();
        var codec = new JacksonChatMessageJsonCodec();
        values.put(
                "chat_memory:" + id,
                codec.messagesToJson(
                        List.of(
                                SystemMessage.from("当前规则"),
                                AiMessage.from(request),
                                ToolExecutionResultMessage.from(request, "残留工具结果"),
                                AiMessage.from("残留回复"),
                                UserMessage.from("重新查这家"),
                                AiMessage.from("有效回复"))));

        var messages = store.getMessages(id);
        assertEquals(3, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertEquals("重新查这家", ((UserMessage) messages.get(1)).singleText());
        assertEquals("有效回复", ((AiMessage) messages.get(2)).text());
    }

    @Test
    void preferenceVersionUsesNewRoleWindowWithoutLosingOriginalSession() {
        store.updateMessages(id, List.of(UserMessage.from("旧偏好"), AiMessage.from("旧回答")));
        String changed = "food:" + MemoryScope.encode(session, 2, 0);
        assertTrue(store.getMessages(changed).isEmpty());
        assertFalse(store.getMessages(id).isEmpty());
        assertEquals(session, MemoryScope.sessionId(MemoryScope.encode(session, 2, 0)));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_INFRA_TESTS", matches = "true")
    void realRedisMemoryHasIndependentSevenDayTtl() {
        var config = new RedisChatMemoryStoreConfig();
        config.setHost(System.getenv().getOrDefault("REDIS_HOST", "localhost"));
        config.setPort(Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "16379")));
        config.setPassword(System.getenv().getOrDefault("REDIS_PASSWORD", ""));
        config.setTimeoutMillis(60000);
        try (var connection = config.jedisPooled()) {
            var real =
                    new RedisChatMemoryStoreConfig.JedisChatMemoryStore(connection, 604800, 6000);
            try {
                real.updateMessages(
                        id, List.of(SystemMessage.from("规则"), UserMessage.from("hello")));
                long ttl = connection.ttl("chat_memory:" + id);
                assertTrue(ttl > 604700 && ttl <= 604800);
                assertEquals(2, real.getMessages(id).size());
            } finally {
                real.deleteMessages(id);
            }
        }
    }

    private void enableAtomicRecoveryWrites() {
        when(redis.set(anyString(), anyString(), any(SetParams.class)))
                .thenAnswer(
                        inv -> {
                            String key = inv.getArgument(0);
                            if (values.containsKey(key)) return null;
                            values.put(key, inv.getArgument(1));
                            return "OK";
                        });
    }

    @Test
    void cacheMissRecoversOnlyOnceAndRetainsNewSystemMessageFirst() {
        enableAtomicRecoveryWrites();
        var loads = new AtomicInteger();
        var recovering =
                new RedisChatMemoryStoreConfig.JedisChatMemoryStore(
                        redis,
                        604800,
                        6000,
                        ignored -> {
                            loads.incrementAndGet();
                            return List.of(
                                    UserMessage.from("之前的问题"), AiMessage.from("上次推荐甲酒店与乙酒店"));
                        });
        var window =
                MessageWindowChatMemory.builder()
                        .id(id)
                        .chatMemoryStore(recovering)
                        .maxMessages(20)
                        .build();
        window.add(SystemMessage.from("当前系统规则"));
        window.add(UserMessage.from("第二家太远，换一家"));
        var result = window.messages();
        assertEquals(1, loads.get());
        assertInstanceOf(SystemMessage.class, result.get(0));
        assertTrue(
                result.stream()
                        .filter(AiMessage.class::isInstance)
                        .map(AiMessage.class::cast)
                        .anyMatch(message -> message.text().contains("乙酒店")));
        assertEquals(
                1,
                result.stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .filter(message -> message.singleText().equals("第二家太远，换一家"))
                        .count());
        verify(redis).set(eq("chat_memory:" + id), anyString(), any(SetParams.class));
    }

    @Test
    void emptyHistoryIsCachedInsteadOfQueryingSqlOnEveryRead() {
        enableAtomicRecoveryWrites();
        var loads = new AtomicInteger();
        var recovering =
                new RedisChatMemoryStoreConfig.JedisChatMemoryStore(
                        redis,
                        604800,
                        6000,
                        ignored -> {
                            loads.incrementAndGet();
                            return List.of();
                        });
        assertTrue(recovering.getMessages(id).isEmpty());
        assertTrue(recovering.getMessages(id).isEmpty());
        assertEquals(1, loads.get());
    }

    @Test
    void recoveryCannotOverwriteWindowWrittenByAnotherCall() {
        enableAtomicRecoveryWrites();
        var codec = new JacksonChatMessageJsonCodec();
        var recovering =
                new RedisChatMemoryStoreConfig.JedisChatMemoryStore(
                        redis,
                        604800,
                        6000,
                        ignored -> {
                            values.put(
                                    "chat_memory:" + id,
                                    codec.messagesToJson(List.of(UserMessage.from("并发请求刚写入的新消息"))));
                            return List.of(UserMessage.from("较早SQL消息"));
                        });
        var result = recovering.getMessages(id);
        assertEquals("并发请求刚写入的新消息", ((UserMessage) result.get(0)).singleText());
    }

    @Test
    void sqlRecoveryFailureDoesNotBreakTheExistingSqlContextPath() {
        var recovering =
                new RedisChatMemoryStoreConfig.JedisChatMemoryStore(
                        redis,
                        604800,
                        6000,
                        ignored -> {
                            throw new IllegalStateException("SQL unavailable");
                        });
        assertTrue(recovering.getMessages(id).isEmpty());
    }

    @Test
    void restoredHistoryActuallyReachesTheNextPlannerModelRequest() {
        enableAtomicRecoveryWrites();
        var recovering =
                new RedisChatMemoryStoreConfig.JedisChatMemoryStore(
                        redis,
                        604800,
                        6000,
                        ignored ->
                                List.of(UserMessage.from("上次查过酒店"), AiMessage.from("1.甲酒店；2.乙酒店")));
        var model = mock(dev.langchain4j.model.chat.ChatModel.class);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenAnswer(
                        inv -> {
                            dev.langchain4j.model.chat.request.ChatRequest request =
                                    inv.getArgument(0);
                            assertInstanceOf(SystemMessage.class, request.messages().get(0));
                            assertTrue(
                                    request.messages().stream()
                                            .filter(AiMessage.class::isInstance)
                                            .map(AiMessage.class::cast)
                                            .anyMatch(message -> message.text().contains("乙酒店")));
                            assertEquals(
                                    1,
                                    request.messages().stream()
                                            .filter(UserMessage.class::isInstance)
                                            .map(UserMessage.class::cast)
                                            .filter(
                                                    message ->
                                                            message.singleText()
                                                                    .equals("第二个太远，换一个"))
                                            .count());
                            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                                    .aiMessage(AiMessage.from("替换第二家酒店"))
                                    .build();
                        });
        var planner =
                dev.langchain4j.service.AiServices.builder(
                                cn.zhixing.travel.agent.accommodation.AccommodationPlannerService
                                        .class)
                        .chatModel(model)
                        .chatMemoryProvider(
                                memoryId ->
                                        MessageWindowChatMemory.builder()
                                                .id("accommodation:" + memoryId)
                                                .chatMemoryStore(recovering)
                                                .maxMessages(20)
                                                .build())
                        .build();
        assertEquals("替换第二家酒店", planner.chat(MemoryScope.encode(session, 1, 0), "第二个太远，换一个"));
    }
}
