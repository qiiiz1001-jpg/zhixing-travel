package cn.zhixing.travel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.zhixing.travel.agent.requirement.*;
import cn.zhixing.travel.agent.state.TravelWorkflowState;

import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

/** Real PostgreSQL checkpoints with explicit model/tool substitutes. */
@EnabledIfEnvironmentVariable(named = "RUN_INFRA_TESTS", matches = "true")
class PostgresCheckpointIntegrationTest {
    private PostgresSaver saver() throws Exception {
        return PostgresSaver.builder()
                .host(System.getenv().getOrDefault("PGVECTOR_HOST", "localhost"))
                .port(Integer.parseInt(System.getenv().getOrDefault("PGVECTOR_PORT", "15432")))
                .database("travel_planner_db")
                .user(System.getenv().getOrDefault("PGVECTOR_USER", "chat"))
                .password(System.getenv("PGVECTOR_PASSWORD"))
                .stateSerializer(new ObjectStreamStateSerializer<>(TravelWorkflowState::new))
                .createTables(false)
                .build();
    }

    @Test
    void newGraphAndNewSaverResumePersistedMissingInformation() throws Exception {
        var firstFixture = new TravelWorkflowTest();
        var secondFixture = new TravelWorkflowTest();
        String session = UUID.randomUUID().toString();
        try {
            firstFixture.setup();
            var first = (RequirementAgent) ReflectionTestUtils.getField(firstFixture, "graph");
            var firstModel =
                    (RequirementAgentService)
                            ReflectionTestUtils.getField(firstFixture, "requirements");
            ReflectionTestUtils.setField(first, "checkpointSaver", saver());
            first.initGraph();
            when(firstModel.chat(eq(session), anyString()))
                    .thenReturn(
                            new RequirementInfo(session, "", true, "origin", new Requirements()));
            assertTrue(first.runState(session, "去上海").missing());

            secondFixture.setup();
            var second = (RequirementAgent) ReflectionTestUtils.getField(secondFixture, "graph");
            var secondModel =
                    (RequirementAgentService)
                            ReflectionTestUtils.getField(secondFixture, "requirements");
            ReflectionTestUtils.setField(second, "checkpointSaver", saver());
            second.initGraph();
            when(secondModel.chat(eq(session), anyString()))
                    .thenReturn(
                            new RequirementInfo(
                                    session,
                                    "",
                                    false,
                                    "",
                                    new Requirements("北京", "上海", "2026-12-01", "2", "安静")));
            var result = second.runState(session, "北京出发");
            assertFalse(result.missing());
            assertTrue(result.output().contains("交通汇总"));
            assertTrue(result.output().contains("住宿方案"));
        } finally {
            firstFixture.close();
            secondFixture.close();
        }
    }

    @Test
    void newGraphModifiesCompletedPersistedPlanAndReusesUnaffectedResults() throws Exception {
        var firstFixture = new TravelWorkflowTest();
        var secondFixture = new TravelWorkflowTest();
        String session = UUID.randomUUID().toString();
        try {
            firstFixture.setup();
            var first = (RequirementAgent) ReflectionTestUtils.getField(firstFixture, "graph");
            var firstModel =
                    (RequirementAgentService)
                            ReflectionTestUtils.getField(firstFixture, "requirements");
            ReflectionTestUtils.setField(first, "checkpointSaver", saver());
            first.initGraph();
            when(firstModel.chat(eq(session), anyString()))
                    .thenReturn(
                            new RequirementInfo(
                                    session,
                                    "",
                                    false,
                                    "",
                                    new Requirements("北京", "上海", "2026-12-01", "2", "安静")));
            first.runState(session, "规划北京到上海");

            secondFixture.setup();
            var second = (RequirementAgent) ReflectionTestUtils.getField(secondFixture, "graph");
            var secondModel =
                    (RequirementAgentService)
                            ReflectionTestUtils.getField(secondFixture, "requirements");
            ReflectionTestUtils.setField(second, "checkpointSaver", saver());
            second.initGraph();
            Requirements patch = new Requirements();
            patch.setFoodPreferences("素食");
            var change = new RequirementInfo(session, "", false, "", patch);
            change.setIntent("MODIFY");
            change.setAffectedBranches(java.util.List.of("food"));
            when(secondModel.chat(eq(session), anyString())).thenReturn(change);
            var result = second.runState(session, "美食改成素食");
            assertEquals("北京", result.requirements().getOrigin());
            assertEquals("素食", result.requirements().getFoodPreferences());
            assertEquals("交通汇总", result.transportOutput());
            assertEquals("住宿方案", result.accommodationOutput());
            verifyNoInteractions(
                    ReflectionTestUtils.getField(secondFixture, "transport"),
                    ReflectionTestUtils.getField(secondFixture, "accommodation"),
                    ReflectionTestUtils.getField(secondFixture, "drive"),
                    ReflectionTestUtils.getField(secondFixture, "train"),
                    ReflectionTestUtils.getField(secondFixture, "flight"));
            verify(
                            (cn.zhixing.travel.agent.food.FoodPlannerAgent)
                                    ReflectionTestUtils.getField(secondFixture, "food"),
                            times(1))
                    .execute(eq(session), any());
        } finally {
            firstFixture.close();
            secondFixture.close();
        }
    }
}
