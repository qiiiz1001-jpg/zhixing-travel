package cn.zhixing.travel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.zhixing.travel.agent.food.FoodPlannerAgent;
import cn.zhixing.travel.agent.requirement.*;
import cn.zhixing.travel.agent.state.TravelWorkflowState;
import cn.zhixing.travel.exception.BusinessException;
import cn.zhixing.travel.memory.*;
import cn.zhixing.travel.memory.MemoryModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.model.chat.ChatModel;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;

/** 真实 PostgreSQL 存档和图调度；摘要、需求分析与规划均用明确替身。 */
@EnabledIfEnvironmentVariable(named = "RUN_INFRA_TESTS", matches = "true")
class MemoryIntegrationTest {
    private final String user = "memory-test-" + UUID.randomUUID();
    private final String session = UUID.randomUUID().toString();
    private final ChatModel model = mock(ChatModel.class);
    private JdbcTemplate jdbc;
    private MemoryRepository repository;
    private TravelMemoryService memory;
    private DriverManagerDataSource source;
    private final ObjectMapper json = new ObjectMapper();
    private TravelWorkflowTest fixture;
    private RequirementAgent graph;
    private RequirementAgentService extraction;

    @BeforeEach
    void setup() throws Exception {
        String host = System.getenv().getOrDefault("PGVECTOR_HOST", "localhost");
        String port = System.getenv().getOrDefault("PGVECTOR_PORT", "15432");
        String database = System.getenv().getOrDefault("TRAVEL_DATABASE", "travel_planner_db");
        source =
                new DriverManagerDataSource(
                        "jdbc:postgresql://" + host + ":" + port + "/" + database,
                        System.getenv().getOrDefault("PGVECTOR_USER", "chat"),
                        System.getenv("PGVECTOR_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("db/04-memory.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        repository = new MemoryRepository(jdbc, json, new DataSourceTransactionManager(source));
        memory = new TravelMemoryService(repository, model, json);
        createSession(session);
        when(model.chat(anyString()))
                .thenReturn(
                        "{\"common\":\"用户这次带老人，尽量少走路\",\"transport\":\"历史交通建议待确认\",\"accommodation\":\"当前住宿区域以最新需求为准\",\"food\":\"只保留本次饮食条件\"}");
    }

    private void createSession(String id) {
        jdbc.update(
                "INSERT INTO conversation(id,user_id,title) VALUES (?,?,'memory integration test')",
                id,
                user);
    }

    @AfterEach
    void cleanup() {
        if (fixture != null) fixture.close();
        if (jdbc != null) {
            jdbc.update(
                    "DELETE FROM message WHERE conversation_id IN (SELECT id FROM conversation"
                            + " WHERE user_id=?)",
                    user);
            jdbc.update("DELETE FROM conversation WHERE user_id=?", user);
            jdbc.update(
                    "DELETE FROM store WHERE namespace=ARRAY['travel-memory','user',?]::text[]",
                    user);
        }
    }

    private Update save(String category, String content) {
        return memory.update(
                user,
                repository.profile(user).version(),
                UUID.randomUUID().toString(),
                List.of(new Operation(category, content == null ? "DELETE" : "SET", content)));
    }

    private String message(String text, short role) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO message(id,conversation_id,role,content) VALUES (?,?,?,?)",
                id,
                session,
                role,
                text);
        return id;
    }

    private void graph() throws Exception {
        fixture = new TravelWorkflowTest();
        fixture.setup();
        graph = (RequirementAgent) ReflectionTestUtils.getField(fixture, "graph");
        extraction =
                (RequirementAgentService) ReflectionTestUtils.getField(fixture, "requirements");
        ReflectionTestUtils.setField(graph, "memoryService", memory);
    }

    private RequirementInfo change(String intent, Requirements requirements) {
        var change = new RequirementInfo(session, "", false, "", requirements);
        change.setIntent(intent);
        return change;
    }

    private TravelWorkflowState run(String text, RequirementInfo change) {
        when(extraction.chat(anyString(), anyString())).thenReturn(change);
        Map<String, Object> context = memory.prepare(user, session);
        context.put("memoryOperationId", message(text, (short) 1));
        return graph.runState(session, text, "", context);
    }

    private Requirements complete() {
        return new Requirements("北京", "西安", "2026-12-01", "2", "带老人，尽量少走路");
    }

    private Object planner(String name) {
        return ReflectionTestUtils.getField(fixture, name);
    }

    @Test
    void preferenceVersionsReceiptsIsolationAndRestart() {
        String operation = UUID.randomUUID().toString();
        var ops = List.of(new Operation("food", "SET", "不吃辣"));
        assertEquals(1, memory.update(user, 0, operation, ops).profile().version());
        save("transport", "优先高铁");
        assertTrue(memory.update(user, 0, operation, ops).replayed());
        assertEquals(2, repository.profile(user).version());
        assertThrows(
                BusinessException.class,
                () -> memory.update(user, 0, UUID.randomUUID().toString(), ops));
        var restarted = new MemoryRepository(jdbc, json, new DataSourceTransactionManager(source));
        assertEquals("不吃辣", restarted.profile(user).preferences().get("food"));
        assertTrue(restarted.profile("other-user").preferences().isEmpty());
        assertThrows(BusinessException.class, () -> memory.prepare("other-user", session));
        assertThrows(
                BusinessException.class,
                () ->
                        memory.update(
                                user,
                                2,
                                UUID.randomUUID().toString(),
                                List.of(new Operation("destination", "SET", "西安"))));
        assertThrows(BusinessException.class, () -> save("food", "辣".repeat(301)));
    }

    @Test
    void concurrentPreferenceEditsCannotSilentlyOverwrite() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (String category : List.of("food", "transport"))
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        memory.update(
                                                user,
                                                0,
                                                UUID.randomUUID().toString(),
                                                List.of(new Operation(category, "SET", "当前完整偏好")));
                                        return true;
                                    } catch (BusinessException conflict) {
                                        assertEquals(409, conflict.getErrorCode());
                                        return false;
                                    }
                                }));
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1, repository.profile(user).version());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void incrementalSummaryUsesServerRangeAndFailureDoesNotAdvanceIt() {
        ReflectionTestUtils.setField(memory, "keepMessages", 2);
        ReflectionTestUtils.setField(memory, "threshold", 2);
        memory.prepare(user, session);
        for (int i = 0; i < 6; i++) message("业务消息 " + i, (short) (i % 2 + 1));
        List<Long> ordered =
                jdbc.queryForList(
                        "SELECT memory_seq FROM message WHERE conversation_id=? ORDER BY"
                                + " memory_seq",
                        Long.class,
                        session);
        memory.summarize(user, session, complete());
        Summary before = repository.summary(user, session);
        assertEquals(ordered.get(3).longValue(), before.coveredSequence());
        assertEquals(0, before.coveredOffset());
        assertTrue(before.sections().get("common").contains("老人"));
        memory.summarize(user, session, complete());
        verify(model, times(1)).chat(anyString());
        for (int i = 0; i < 4; i++) message("新增业务消息 " + i, (short) (i % 2 + 1));
        doThrow(new IllegalStateException("model unavailable")).when(model).chat(anyString());
        memory.summarize(user, session, complete());
        assertEquals(before, repository.summary(user, session));
        doReturn("{\"common\":\"改目的地后只参考当前西安行程\"}").when(model).chat(anyString());
        memory.summarize(user, session, complete());
        assertTrue(repository.summary(user, session).coveredSequence() > before.coveredSequence());
        verify(model, atLeastOnce())
                .chat(
                        argThat(
                                (String prompt) ->
                                        prompt.contains("西安")
                                                && prompt.contains("previousSummary")));
    }

    @Test
    void longMessagesUsePartialCursorAndDoNotMarkUnprocessedTextCovered() {
        ReflectionTestUtils.setField(memory, "keepMessages", 2);
        ReflectionTestUtils.setField(memory, "threshold", 2);
        ReflectionTestUtils.setField(memory, "maxInput", 1000);
        memory.prepare(user, session);
        message("较早的重要背景".repeat(1500), (short) 1);
        message("确认收到", (short) 2);
        message("最近的追问", (short) 1);
        message("最近的回答", (short) 2);
        memory.summarize(user, session, complete());
        Summary first = repository.summary(user, session);
        assertTrue(first.coveredOffset() > 0);
        assertTrue(first.coveredOffset() < 10000);
        verify(model).chat(argThat((String prompt) -> prompt.length() <= 1000));
        memory.summarize(user, session, complete());
        Summary second = repository.summary(user, session);
        assertEquals(first.coveredSequence(), second.coveredSequence());
        assertTrue(second.coveredOffset() > first.coveredOffset());
    }

    @Test
    void deletingPreferenceResetsSummaryAndPreventsReadingOldUtterances() {
        save("food", "不吃辣");
        memory.prepare(user, session);
        message("以后默认不辣", (short) 1);
        message("已记住不辣", (short) 2);
        save("food", null);
        Map<String, Object> context = memory.prepare(user, session);
        assertTrue(((Map<?, ?>) context.get("summarySections")).isEmpty());
        assertTrue(((List<?>) context.get("recentConversation")).isEmpty());
        assertEquals(
                repository.latestSequence(session),
                repository.summary(user, session).floorSequence());
        assertNotEquals(MemoryScope.encode(session, 1, 0), MemoryScope.encode(session, 2, 0));
        assertEquals(session, MemoryScope.sessionId(MemoryScope.encode(session, 2, 0)));
    }

    @Test
    void pureRememberAndForgetDoNotAskForTripOrCallPlanners() throws Exception {
        graph();
        var change = change("RETRY", new Requirements());
        change.setMemoryOnly(true);
        change.setMemoryOperations(List.of(new Operation("food", "SET", "不吃辣")));
        var result = run("以后不辣，帮我记住", change);
        assertEquals("MEMORY", result.<String>value("responseType").orElseThrow());
        assertTrue(result.output().contains("已记住美食"));
        assertEquals("不吃辣", repository.profile(user).preferences().get("food"));
        assertNull(result.requirements().getFoodPreferences());
        verifyNoInteractions(planner("food"), planner("accommodation"), planner("transport"));
        change.setMemoryOperations(List.of(new Operation("food", "DELETE", "")));
        assertTrue(run("忘记我的美食偏好", change).output().contains("已忘记"));
        assertTrue(repository.profile(user).preferences().isEmpty());
    }

    @Test
    void currentTripOverridesDefaultsAndDefaultChangesOnlyRerunInheritedBranch() throws Exception {
        save("food", "不吃辣");
        graph();
        var first = run("北京到西安，两人，12月1日，带老人", change("NEW", complete()));
        assertNull(first.requirements().getFoodPreferences());
        assertEquals("不吃辣", first.effectiveRequirements().getFoodPreferences());
        save("food", "素食");
        clearInvocations(planner("food"), planner("accommodation"), planner("transport"));
        var second = run("重试", change("RETRY", new Requirements()));
        assertEquals("素食", second.effectiveRequirements().getFoodPreferences());
        verify((FoodPlannerAgent) planner("food"), times(1)).execute(anyString(), any());
        verifyNoInteractions(planner("accommodation"), planner("transport"));
        Requirements patch = new Requirements();
        patch.setFoodPreferences("这次尝试辣菜");
        var third = run("这次尝试辣菜", change("MODIFY", patch));
        assertEquals("这次尝试辣菜", third.effectiveRequirements().getFoodPreferences());
        assertEquals("素食", repository.profile(user).preferences().get("food"));
        save("food", "清淡");
        clearInvocations(planner("food"), planner("accommodation"), planner("transport"));
        var fourth = run("重试", change("RETRY", new Requirements()));
        assertEquals("这次尝试辣菜", fourth.effectiveRequirements().getFoodPreferences());
        verifyNoInteractions(planner("food"), planner("accommodation"), planner("transport"));
    }

    @Test
    void ignoreAndResumeDefaultsAreExplicitModes() throws Exception {
        save("food", "不吃辣");
        graph();
        run("开始规划", change("NEW", complete()));
        var ignored = change("MODIFY", new Requirements());
        ignored.setPreferenceModes(Map.of("food", "IGNORE"));
        assertNull(run("这次不参考历史美食偏好", ignored).effectiveRequirements().getFoodPreferences());
        var inherited = change("MODIFY", new Requirements());
        inherited.setPreferenceModes(Map.of("food", "INHERIT"));
        assertEquals(
                "不吃辣", run("重新沿用平时口味", inherited).effectiveRequirements().getFoodPreferences());
    }

    @Test
    void ordinaryTripConditionCannotSilentlyBecomeLongTermPreference() throws Exception {
        graph();
        Requirements requirements = complete();
        requirements.setFoodPreferences("不吃辣");
        var change = change("NEW", requirements);
        change.setMemoryOperations(List.of(new Operation("food", "SET", "不吃辣")));
        var state = run("这次不辣", change);
        assertTrue(repository.profile(user).preferences().isEmpty());
        assertFalse(state.output().contains("已记住"));
    }

    @Test
    void concurrentRelevantPreferenceChangeStopsPublishingPlan() {
        save("food", "不辣");
        Requirements raw = complete();
        Requirements effective =
                EffectivePreferences.resolve(raw, repository.profile(user).preferences(), Map.of());
        var state =
                new TravelWorkflowState(
                        Map.of(
                                "memoryUserId",
                                user,
                                "requirements",
                                raw,
                                "effectiveRequirements",
                                effective));
        save("food", "素食");
        var published = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(
                BusinessException.class,
                () -> memory.publishPlan(state, () -> published.set(true)));
        assertFalse(published.get());
    }

    @Test
    void updatingOnlySummaryDoesNotInvalidateUnchangedPlan() throws Exception {
        graph();
        run("完整规划", change("NEW", complete()));
        Summary old = repository.summary(user, session);
        repository.saveSummary(
                user,
                session,
                old,
                new Summary(
                        old.contextVersion(),
                        old.revision() + 1,
                        old.floorSequence(),
                        old.coveredSequence(),
                        old.coveredOffset(),
                        Map.of("common", "摘要更新了当前背景")));
        clearInvocations(planner("food"), planner("accommodation"), planner("transport"));
        run("重试", change("RETRY", new Requirements()));
        verifyNoInteractions(planner("food"), planner("accommodation"), planner("transport"));
    }

    @Test
    void newConversationUsesLongTermDefaultsAndCanDeleteThemWithoutChangingExplicitTrip()
            throws Exception {
        save("transport", "优先高铁");
        String another = UUID.randomUUID().toString();
        createSession(another);
        var context = memory.prepare(user, another);
        assertEquals("优先高铁", ((Map<?, ?>) context.get("longTermPreferences")).get("transport"));
        Requirements raw = complete();
        raw.setFoodPreferences("本次吃辣");
        save("food", "不辣");
        graph();
        run("这次吃辣", change("NEW", raw));
        var deletion = change("RETRY", new Requirements());
        deletion.setMemoryOnly(true);
        deletion.setMemoryOperations(List.of(new Operation("food", "DELETE", "")));
        run("忘记我的美食偏好", deletion);
        clearInvocations(planner("food"), planner("accommodation"), planner("transport"));
        assertEquals(
                "本次吃辣",
                run("重试", change("RETRY", new Requirements()))
                        .effectiveRequirements()
                        .getFoodPreferences());
        verifyNoInteractions(planner("food"), planner("accommodation"), planner("transport"));
    }

    @Test
    void preferencePanelUsesAuthenticatedUserAndVersionedUpdates() throws Exception {
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new cn.zhixing.travel.controller.MemoryController(memory))
                        .setControllerAdvice(
                                new cn.zhixing.travel.exception.GlobalExceptionHandler())
                        .build();
        cn.zhixing.travel.utils.RequestContext.set(
                new cn.zhixing.travel.utils.RequestContextInfo(user));
        try {
            String body =
                    json.writeValueAsString(
                            Map.of(
                                    "expectedVersion",
                                    0,
                                    "operationId",
                                    UUID.randomUUID().toString(),
                                    "preferences",
                                    Map.of("food", "不辣")));
            String response =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.put("/memory/preferences")
                                            .contentType("application/json")
                                            .content(body))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(json.readTree(response).path("success").asBoolean());
            assertEquals("不辣", repository.profile(user).preferences().get("food"));
            assertTrue(repository.profile("other-user").preferences().isEmpty());
        } finally {
            cn.zhixing.travel.utils.RequestContext.remove();
        }
    }

    @Test
    void referenceFollowupReceivesPreviousOptionsAndOnlyReplansAccommodation() throws Exception {
        graph();
        var hotels =
                (cn.zhixing.travel.agent.accommodation.AccommodationPlannerAgent)
                        planner("accommodation");
        when(hotels.execute(anyString(), any())).thenReturn("【酒店名】：1.甲酒店\n【酒店名】：2.乙酒店");
        run("开始规划", change("NEW", complete()));
        clearInvocations(planner("food"), planner("accommodation"), planner("transport"));
        var patch = new Requirements();
        patch.setAccommodationPreferences("替换乙酒店，选择更近的位置");
        run("第二个酒店太远，换一个", change("MODIFY", patch));
        verify(hotels)
                .execute(
                        anyString(),
                        argThat(req -> req.getAccommodationPreferences().contains("乙酒店")));
        verifyNoInteractions(planner("food"), planner("transport"));
        verify(extraction, atLeastOnce())
                .chat(
                        anyString(),
                        argThat(
                                (String prompt) ->
                                        prompt.contains("乙酒店")
                                                && prompt.contains("previousSuggestions")));
    }

    @Test
    void failedPreferenceWriteCannotReturnSuccessfulMemoryReceipt() throws Exception {
        var failing = spy(repository);
        doThrow(new IllegalStateException("write unavailable"))
                .when(failing)
                .update(anyString(), anyLong(), anyString(), anyList());
        memory = new TravelMemoryService(failing, model, json);
        graph();
        var operation = change("RETRY", new Requirements());
        operation.setMemoryOnly(true);
        operation.setMemoryOperations(List.of(new Operation("food", "SET", "不辣")));
        assertThrows(IllegalStateException.class, () -> run("帮我记住以后不辣", operation));
        assertTrue(repository.profile(user).preferences().isEmpty());
        verifyNoInteractions(planner("food"), planner("accommodation"), planner("transport"));
    }
}
