package cn.zhixing.travel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.zhixing.travel.agent.requirement.RequirementAgent;
import cn.zhixing.travel.agent.requirement.Requirements;
import cn.zhixing.travel.agent.state.TravelWorkflowState;
import cn.zhixing.travel.knowledge.KnowledgeRepository;
import cn.zhixing.travel.mapper.*;
import cn.zhixing.travel.memory.*;
import cn.zhixing.travel.model.Conversation;
import cn.zhixing.travel.service.*;
import cn.zhixing.travel.service.impl.ChatServiceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

class MemoryChatServiceTest {
    @Test
    void memoryOnlyReplyNeverOverwritesLatestPlanEvenWhenOldMissingIsFalse() {
        String session = UUID.randomUUID().toString();
        var conversations = mock(ConversationMapper.class);
        var messages = mock(MessageService.class);
        var store = mock(StoreMapper.class);
        var graph = mock(RequirementAgent.class);
        var knowledge = mock(KnowledgeRepository.class);
        var memory = mock(TravelMemoryService.class);
        when(conversations.selectById(session))
                .thenReturn(Conversation.builder().userId("owner").isDelete((short) 0).build());
        when(memory.prepare("owner", session))
                .thenReturn(new HashMap<>(Map.of("memoryUserId", "owner")));
        when(knowledge.revision("owner")).thenReturn("");
        when(graph.runState(eq(session), anyString(), eq(""), anyMap()))
                .thenReturn(
                        new TravelWorkflowState(
                                Map.of(
                                        "responseType",
                                        "MEMORY",
                                        "missing",
                                        false,
                                        "output",
                                        "已记住美食偏好",
                                        "requirements",
                                        new Requirements())));
        var service = new ChatServiceImpl();
        Object[][] fields = {
            {"conversationMapper", conversations},
            {"messageService", messages},
            {"storeMapper", store},
            {"requirementAgent", graph},
            {"knowledge", knowledge},
            {"memory", memory},
            {"objectMapper", new ObjectMapper()},
            {"executionLocks", new ConversationExecutionLocks()}
        };
        for (Object[] field : fields)
            ReflectionTestUtils.setField(service, (String) field[0], field[1]);
        assertEquals(
                "已记住美食偏好",
                service.chat("owner", session, "以后不辣，帮我记住").collectList().block().get(0));
        verifyNoInteractions(store);
        verify(memory, never()).publishPlan(any(), any());
        verify(memory).summarize(eq("owner"), eq(session), any());
    }

    @Test
    void hotelEditStoresOnlyTheChangedReplyAndKeepsTheFullPlan() throws Exception {
        String session = UUID.randomUUID().toString();
        var conversations = mock(ConversationMapper.class);
        var messages = mock(MessageService.class);
        var store = mock(StoreMapper.class);
        var graph = mock(RequirementAgent.class);
        var knowledge = mock(KnowledgeRepository.class);
        var memory = mock(TravelMemoryService.class);
        when(conversations.selectById(session))
                .thenReturn(Conversation.builder().userId("owner").isDelete((short) 0).build());
        when(memory.prepare("owner", session)).thenReturn(new HashMap<>());
        when(knowledge.revision("owner")).thenReturn("");
        String full = "行程汇总：交通旧结果、酒店新结果、餐厅旧结果";
        var state =
                new TravelWorkflowState(
                        Map.of(
                                "responseType",
                                "PLAN",
                                "intent",
                                "MODIFY",
                                "hadPreviousPlan",
                                true,
                                "affectedBranches",
                                List.of("accommodation"),
                                "output",
                                full,
                                "transportOutput",
                                "交通旧结果",
                                "accommodationOutput",
                                "酒店新结果",
                                "foodOutput",
                                "餐厅旧结果"));
        when(graph.runState(eq(session), anyString(), eq(""), anyMap())).thenReturn(state);
        doAnswer(
                        invocation -> {
                            invocation.<Runnable>getArgument(1).run();
                            return null;
                        })
                .when(memory)
                .publishPlan(eq(state), any());
        var service = new ChatServiceImpl();
        var json = new ObjectMapper();
        Object[][] fields = {
            {"conversationMapper", conversations},
            {"messageService", messages},
            {"storeMapper", store},
            {"requirementAgent", graph},
            {"knowledge", knowledge},
            {"memory", memory},
            {"objectMapper", json},
            {"executionLocks", new ConversationExecutionLocks()}
        };
        for (Object[] field : fields)
            ReflectionTestUtils.setField(service, (String) field[0], field[1]);

        String reply = "【住宿方案更新】\n酒店新结果";
        assertEquals(reply, service.chat("owner", session, "住宿便宜一点").blockFirst());
        verify(messages).save(argThat(m -> m.getRole() == 2 && reply.equals(m.getContent())));
        var stored = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store).upsert(any(String[].class), eq("latest_plan"), stored.capture());
        var plan = json.readTree(stored.getValue());
        assertEquals(full, plan.path("output").asText());
        assertEquals("交通旧结果", plan.path("transportOutput").asText());
        assertEquals("酒店新结果", plan.path("accommodationOutput").asText());
        assertEquals("餐厅旧结果", plan.path("foodOutput").asText());
    }

    @Test
    void profileReadFailureDoesNotInjectOldPersonalization() {
        var repository = mock(MemoryRepository.class);
        when(repository.profile("owner"))
                .thenThrow(new IllegalStateException("profile unavailable"));
        var memory =
                new TravelMemoryService(
                        repository,
                        mock(dev.langchain4j.model.chat.ChatModel.class),
                        new ObjectMapper());
        var context = memory.prepare("owner", "session");
        assertEquals(Map.of(), context.get("longTermPreferences"));
        assertEquals(Map.of(), context.get("summarySections"));
        assertTrue(context.get("memoryNotice").toString().contains("未应用"));
        assertEquals(-1L, context.get("preferenceVersion"));
    }
}
