package cn.zhixing.travel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.zhixing.travel.agent.accommodation.*;
import cn.zhixing.travel.agent.food.*;
import cn.zhixing.travel.agent.requirement.*;
import cn.zhixing.travel.agent.transportation.*;
import cn.zhixing.travel.agent.transportation.subagents.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.concurrent.*;

/** 真正执行图调度，模型和外部工具用明确的测试替身，不计作真实模型验证。 */
class TravelWorkflowTest {
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final ExecutorService transportPool = Executors.newFixedThreadPool(3);
    private final RequirementAgentService requirements = mock(RequirementAgentService.class);
    private final RequirementAgent graph = new RequirementAgent();
    private final Requirements complete = new Requirements("北京", "上海", "2026-12-01", "2", "安静");

    private final TransportPlannerAgent transport = mock(TransportPlannerAgent.class);
    private final AccommodationPlannerAgent accommodation = mock(AccommodationPlannerAgent.class);
    private final FoodPlannerAgent food = mock(FoodPlannerAgent.class);
    private final DrivingTransportSubAgent drive = mock(DrivingTransportSubAgent.class);
    private final TrainTransportSubAgent train = mock(TrainTransportSubAgent.class);
    private final FlightTransportSubAgent flight = mock(FlightTransportSubAgent.class);

    @BeforeEach
    void setup() throws Exception {
        ReflectionTestUtils.setField(graph, "taskExecutor", pool);
        ReflectionTestUtils.setField(graph, "transportExecutor", transportPool);
        ReflectionTestUtils.setField(graph, "checkpointSaver", new MemorySaver());
        ReflectionTestUtils.setField(graph, "requirementAgentService", requirements);
        when(transport.execute(anyString(), any())).thenReturn("交通汇总");
        when(accommodation.execute(anyString(), any())).thenReturn("住宿方案");
        when(food.execute(anyString(), any())).thenReturn("美食方案");
        when(drive.execute(anyString(), any())).thenReturn("自驾");
        when(train.execute(anyString(), any())).thenReturn("火车");
        when(flight.execute(anyString(), any())).thenReturn("飞机");
        ReflectionTestUtils.setField(graph, "transportPlannerAgent", transport);
        ReflectionTestUtils.setField(graph, "accommodationPlannerAgent", accommodation);
        ReflectionTestUtils.setField(graph, "foodPlannerAgent", food);
        ReflectionTestUtils.setField(graph, "drivingTransportSubAgent", drive);
        ReflectionTestUtils.setField(graph, "trainTransportSubAgent", train);
        ReflectionTestUtils.setField(graph, "flightTransportSubAgent", flight);
        graph.initGraph();
    }

    @AfterEach
    void close() {
        pool.shutdownNow();
        transportPool.shutdownNow();
    }

    @Test
    void completeRequirementsProduceAllBranches() {
        when(requirements.chat(anyString(), anyString()))
                .thenAnswer(
                        inv -> new RequirementInfo(inv.getArgument(0), "", false, "", complete));
        var state = graph.runState(UUID.randomUUID().toString(), "北京去上海两人");
        assertTrue(state.output().contains("交通汇总"));
        assertTrue(state.output().contains("住宿方案"));
        assertTrue(state.output().contains("美食方案"));
        assertEquals("火车", state.trainTransportOutput());
    }

    @Test
    void provinceWaitsForCityThenContinuesWithOriginalRequirements() {
        String session = UUID.randomUUID().toString();
        when(requirements.chat(eq(session), anyString()))
                .thenReturn(
                        change("NEW", new Requirements("威海", "黑龙江", "后天", "5", "无")),
                        change("MODIFY", new Requirements(null, "哈尔滨", null, null, null)));

        var pending = graph.runState(session, "威海出发去黑龙江，后天五人");
        assertTrue(pending.missing());
        assertEquals("destination", pending.firstMissing());
        assertTrue(pending.output().contains("「黑龙江」的具体城市"));
        verifyNoInteractions(drive, train, flight, accommodation, food);

        var planned = graph.runState(session, "哈尔滨");
        assertEquals("威海", planned.requirements().getOrigin());
        assertEquals("哈尔滨", planned.requirements().getDestination());
        assertEquals("5", planned.requirements().getPeople());
        assertFalse(planned.missing());
        verify(accommodation)
                .execute(eq(session), argThat(input -> "哈尔滨".equals(input.getDestination())));
    }

    @Test
    void moreFoodUsesExistingPreferencesAndAppendsOnlyNewRecommendations() {
        String session = UUID.randomUUID().toString();
        var trip = new Requirements("威海", "济南", "2026-11-26", "5", "3天");
        trip.setFoodPreferences("当地特色");
        when(requirements.chat(eq(session), anyString())).thenReturn(change("NEW", trip));
        String original =
                """
                按当地菜品查询 · 高德地点

                - 把子肉 · 旧推荐甲 · 旧地址
                - 甜沫 · 旧推荐乙 · 旧地址
                """;
        String found =
                """
                按当地菜品查询 · 高德地点

                - 把子肉 · 旧推荐甲 · 地址写法变化
                - 九转大肠 · 新推荐甲 · 新地址
                - 糖醋鲤鱼 · 新推荐乙 · 新地址
                """;
        when(food.execute(anyString(), any())).thenReturn(original, found, "暂未找到新的餐厅。");
        graph.runState(session, "威海去济南，11月26日，五个人玩三天，想吃当地特色");
        var more = graph.runState(session, "美食多来点");
        assertEquals("当地特色", more.effectiveRequirements().getFoodPreferences());
        assertEquals("MORE", more.<String>value("foodAction").orElseThrow());
        assertTrue(more.foodOutput().contains("旧推荐乙"));
        assertTrue(more.foodOutput().contains("新推荐甲"));
        assertEquals(1, more.foodOutput().lines().filter(line -> line.contains("旧推荐甲")).count());
        String reply = more.<String>value("foodReply").orElseThrow();
        assertTrue(reply.contains("新增 2 项推荐"));
        assertFalse(reply.contains("旧推荐"));
        verify(food)
                .execute(
                        eq(session),
                        argThat(
                                input ->
                                        input
                                                        instanceof
                                                        cn.zhixing.travel.memory.MemoryModels
                                                                                .PlannerInput
                                                                        context
                                                && "美食多来点".equals(context.getUserRequest())
                                                && context.getFoodPreviousOutput()
                                                        .contains("旧推荐乙")));
        assertEquals(more.foodOutput(), graph.runState(session, "美食再多来点").foodOutput());
        verify(requirements, times(1)).chat(eq(session), anyString());
        verify(accommodation, times(1)).execute(anyString(), any());
    }

    @Test
    void replyingWithPassengerCountStillFinishesWhenTicketLookupFails() {
        String session = UUID.randomUUID().toString();
        var partial = new Requirements("咸阳", "西安", "后天", null, "无");
        var passengerCount = new Requirements(null, null, null, "3", null);
        var first = new RequirementInfo(session, "", true, "people", partial);
        var next = new RequirementInfo(session, "3", false, "", passengerCount);
        next.setIntent("MODIFY");
        when(requirements.chat(anyString(), anyString())).thenReturn(first, next);
        when(train.execute(anyString(), any()))
                .thenThrow(new UncheckedIOException(new IOException("ticket tool unavailable")));
        when(transport.execute(anyString(), any()))
                .thenThrow(
                        new UncheckedIOException(
                                new IOException("summary temporarily unavailable")));

        assertTrue(graph.runState(session, "出发时间后天").output().contains("出行人数"));
        var completed = graph.runState(session, "3");

        assertEquals("3", completed.requirements().getPeople());
        assertTrue(completed.transportOutput().contains("未查询到火车票实时信息"));
        assertTrue(completed.transportOutput().contains("自驾"));
        assertTrue(completed.output().contains("美食方案"));
        assertTrue(completed.output().contains("住宿方案"));
    }

    @Test
    void toolRoundLimitKeepsOtherBranchesAvailable() {
        when(requirements.chat(anyString(), anyString())).thenReturn(change("NEW", complete));
        when(food.execute(anyString(), any()))
                .thenThrow(
                        new RuntimeException(
                                "Something is wrong, exceeded 5 sequential tool invocations"));

        var state = graph.runState(UUID.randomUUID().toString(), "规划");

        assertTrue(state.foodOutput().contains("暂时无法查询实时餐厅信息"));
        assertTrue(state.output().contains("交通汇总"));
        assertTrue(state.output().contains("住宿方案"));
    }

    @Test
    void modelBudgetFieldIsPreservedInGlobalRequirements() throws Exception {
        var extracted =
                new ObjectMapper()
                        .readValue(
                                """
                                {"intent":"NEW","requirements":{"origin":"咸阳","destination":"西安",
                                  "startDate":"2026-11-28","people":"2","budget":"1500元",
                                  "foodPreferences":"羊肉泡馍","special":"无"}}
                                """,
                                RequirementInfo.class);
        when(requirements.chat(anyString(), anyString())).thenReturn(extracted);

        var state = graph.runState(UUID.randomUUID().toString(), "计划去西安，总预算1500元");

        assertEquals("总预算：1500元", state.requirements().getSpecial());
        assertEquals("羊肉泡馍", state.requirements().getFoodPreferences());
        assertTrue(state.output().contains("美食方案"));
    }

    @Test
    void missingInformationPausesThenResumesSameSession() {
        String session = UUID.randomUUID().toString();
        when(requirements.chat(eq(session), anyString()))
                .thenReturn(new RequirementInfo(session, "", true, "origin", new Requirements()))
                .thenReturn(new RequirementInfo(session, "", false, "", complete));
        var first = graph.runState(session, "去上海");
        assertTrue(first.missing());
        assertFalse(first.output().isBlank());
        var second = graph.runState(session, "从北京出发");
        assertFalse(second.missing());
        assertTrue(second.output().contains("住宿方案"));
        verify(requirements, times(2)).chat(eq(session), anyString());
    }

    private RequirementInfo change(String intent, Requirements patch, String... branches) {
        var info = new RequirementInfo("", "", false, "", patch);
        info.setIntent(intent);
        info.setAffectedBranches(java.util.List.of(branches));
        return info;
    }

    private String completedSession() {
        String session = UUID.randomUUID().toString();
        when(requirements.chat(eq(session), anyString())).thenReturn(change("NEW", complete));
        graph.runState(session, "从北京到上海，12月1日两人，偏好安静");
        return session;
    }

    private void verifyCalls(int transportCount, int accommodationCount, int foodCount) {
        verify(transport, times(transportCount)).execute(anyString(), any());
        verify(drive, times(transportCount)).execute(anyString(), any());
        verify(train, times(transportCount)).execute(anyString(), any());
        verify(flight, times(transportCount)).execute(anyString(), any());
        verify(accommodation, times(accommodationCount)).execute(anyString(), any());
        verify(food, times(foodCount)).execute(anyString(), any());
    }

    @Test
    void completedPlanFoodChangeReusesOtherBranchesAndMergesOldRequirements() {
        String session = completedSession();
        Requirements patch = new Requirements();
        patch.setFoodPreferences("清淡，不吃辣");
        when(requirements.chat(eq(session), anyString()))
                .thenReturn(change("MODIFY", patch, "food"));
        when(food.execute(eq(session), any())).thenReturn("清淡美食新方案");
        var state = graph.runState(session, "只把美食改成清淡的");
        assertEquals("北京", state.requirements().getOrigin());
        assertEquals("上海", state.requirements().getDestination());
        assertEquals("清淡，不吃辣", state.requirements().getFoodPreferences());
        assertEquals("安静", state.requirements().getSpecial());
        assertTrue(state.output().contains("清淡美食新方案"));
        assertTrue(state.output().contains("交通汇总"));
        assertTrue(state.output().contains("住宿方案"));
        verifyCalls(1, 1, 2);
        verify(requirements, times(2)).chat(eq(session), anyString());
        verify(food).execute(eq(session), argThat(r -> "清淡，不吃辣".equals(r.getFoodPreferences())));
    }

    @Test
    void destinationChangeInvalidatesAllEvenIfModelUnderestimatesImpact() {
        String session = completedSession();
        Requirements patch = new Requirements();
        patch.setDestination("西安");
        when(requirements.chat(eq(session), anyString()))
                .thenReturn(change("MODIFY", patch, "food"));
        var state = graph.runState(session, "目的地改西安");
        assertEquals("西安", state.requirements().getDestination());
        verifyCalls(2, 2, 2);
    }

    @Test
    void identicalRetryChecksRequirementsButReusesEveryResult() {
        String session = completedSession();
        when(requirements.chat(eq(session), anyString()))
                .thenReturn(change("RETRY", new Requirements()));
        var state = graph.runState(session, "重试刚才的请求");
        assertTrue(state.output().contains("美食方案"));
        verifyCalls(1, 1, 1);
        verify(requirements, times(2)).chat(eq(session), anyString());
    }

    @Test
    void changedKnowledgeInvalidatesPlanButSameRevisionReusesIt() {
        String session = completedSession();
        when(requirements.chat(eq(session), anyString()))
                .thenReturn(change("RETRY", new Requirements()));
        graph.runState(session, "重试", "new-document-version");
        verifyCalls(2, 2, 2);
        graph.runState(session, "重试", "new-document-version");
        verifyCalls(2, 2, 2);
        graph.runState(session, "重试", ""); // 删除所有资料也必须重新规划。
        verifyCalls(3, 3, 3);
    }

    @Test
    void originChangeOnlyReexecutesTransportSubgraph() {
        String session = completedSession();
        Requirements patch = new Requirements();
        patch.setOrigin("广州");
        when(requirements.chat(eq(session), anyString())).thenReturn(change("MODIFY", patch));
        assertEquals("广州", graph.runState(session, "改广州出发").requirements().getOrigin());
        verifyCalls(2, 1, 1);
    }

    @Test
    void multipleBranchChangesReuseTransport() {
        String session = completedSession();
        Requirements patch = new Requirements();
        patch.setFoodPreferences("素食");
        patch.setAccommodationPreferences("含早");
        when(requirements.chat(eq(session), anyString())).thenReturn(change("MODIFY", patch));
        graph.runState(session, "餐厅素食，酒店含早");
        verifyCalls(1, 2, 2);
    }

    @Test
    void forcedFoodRefreshWorksWithoutFieldChanges() {
        String session = completedSession();
        when(requirements.chat(eq(session), anyString()))
                .thenReturn(change("MODIFY", new Requirements(), "food"));
        graph.runState(session, "换几家餐厅");
        verifyCalls(1, 1, 2);
    }

    @Test
    void newTripDoesNotBorrowOldFieldsAndResumesWithInvalidatedBranches() {
        String session = completedSession();
        Requirements patch = new Requirements();
        patch.setDestination("深圳");
        when(requirements.chat(eq(session), anyString())).thenReturn(change("NEW", patch));
        var pending = graph.runState(session, "新建去深圳的行程");
        assertTrue(pending.missing());
        assertNull(pending.requirements().getOrigin());
        assertEquals("origin", pending.firstMissing());
        assertEquals("", pending.transportOutput());
        assertEquals("", pending.accommodationOutput());
        assertEquals("", pending.foodOutput());
        verifyCalls(1, 1, 1);
        Requirements fill = new Requirements("广州", null, "2026-12-08", "3", "无");
        when(requirements.chat(eq(session), anyString())).thenReturn(change("MODIFY", fill));
        var finished = graph.runState(session, "广州出发，12月8日三人，无特殊要求");
        assertFalse(finished.missing());
        assertEquals("深圳", finished.requirements().getDestination());
        verifyCalls(2, 2, 2);
    }

    @Test
    void clearingRequiredDatePausesAndDoesNotReuseStaleResultsAfterResume() {
        String session = completedSession();
        var clear = change("MODIFY", new Requirements());
        clear.setClearFields(java.util.List.of("startDate"));
        when(requirements.chat(eq(session), anyString())).thenReturn(clear);
        var pending = graph.runState(session, "先取消原日期");
        assertTrue(pending.missing());
        assertEquals("startDate", pending.firstMissing());
        assertTrue(pending.foodOutput().isBlank());
        verifyCalls(1, 1, 1);
        Requirements date = new Requirements();
        date.setStartDate("2026-12-09");
        when(requirements.chat(eq(session), anyString())).thenReturn(change("MODIFY", date));
        assertFalse(graph.runState(session, "改成12月9日").missing());
        verifyCalls(2, 2, 2);
    }

    @Test
    void explicitNewTripReexecutesEvenWhenFieldsAreIdentical() {
        String session = completedSession();
        when(requirements.chat(eq(session), anyString())).thenReturn(change("NEW", complete));
        graph.runState(session, "按同样条件新建一次规划");
        verifyCalls(2, 2, 2);
    }

    @Test
    void emptyBranchResultCannotBePublishedAsCompletePlan() {
        when(requirements.chat(anyString(), anyString())).thenReturn(change("NEW", complete));
        when(food.execute(anyString(), any())).thenReturn("");
        assertThrows(
                IllegalStateException.class,
                () -> graph.runState(UUID.randomUUID().toString(), "规划"));
    }

    @Test
    void newMessageAfterBranchFailureStillPassesThroughChangeAnalysis() {
        String session = UUID.randomUUID().toString();
        when(requirements.chat(eq(session), anyString())).thenReturn(change("NEW", complete));
        when(food.execute(anyString(), any())).thenThrow(new IllegalStateException("工具异常"));
        assertThrows(IllegalStateException.class, () -> graph.runState(session, "第一次规划"));
        Requirements patch = new Requirements();
        patch.setDestination("西安");
        when(requirements.chat(eq(session), anyString())).thenReturn(change("MODIFY", patch));
        doReturn("西安美食").when(food).execute(anyString(), any());
        var result = graph.runState(session, "改成西安");
        assertEquals("西安", result.requirements().getDestination());
        verify(requirements, times(2)).chat(eq(session), anyString());
    }
}
