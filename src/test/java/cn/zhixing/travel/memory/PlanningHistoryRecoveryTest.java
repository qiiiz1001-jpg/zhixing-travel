package cn.zhixing.travel.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.zhixing.travel.mapper.ConversationMapper;
import cn.zhixing.travel.memory.MemoryModels.*;
import cn.zhixing.travel.model.Conversation;

import dev.langchain4j.data.message.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class PlanningHistoryRecoveryTest {
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final MemoryRepository repository = mock(MemoryRepository.class);
    private final PlanningHistoryRecovery recovery =
            new PlanningHistoryRecovery(conversations, repository);
    private final String id = "food:" + MemoryScope.encode("session", 3, 1);

    @BeforeEach
    void setup() {
        when(conversations.selectById("session"))
                .thenReturn(Conversation.builder().userId("owner").isDelete((short) 0).build());
        when(repository.profile("owner")).thenReturn(new Profile(3, Map.of(), "", ""));
        when(repository.summary("owner", "session")).thenReturn(Summary.empty(3, 4, 10));
        when(repository.completedRecent(eq("session"), eq(10L), anyInt()))
                .thenReturn(
                        List.of(
                                new History(11, (short) 1, "上次的问题"),
                                new History(12, (short) 2, "上次的回答")));
    }

    @Test
    void restoresOnlyOwnedCurrentVersionAndRespectsFloor() {
        var result = recovery.recover(id);
        assertEquals(2, result.size());
        assertEquals("上次的问题", ((UserMessage) result.get(0)).singleText());
        verify(repository).completedRecent("session", 10, 17);
    }

    @Test
    void changedPreferenceVersionNeverReplaysOldHistory() {
        when(repository.profile("owner")).thenReturn(new Profile(4, Map.of(), "", ""));
        assertTrue(recovery.recover(id).isEmpty());
        verify(repository, never()).completedRecent(anyString(), anyLong(), anyInt());
    }

    @Test
    void newScopeWithoutTrustworthyBoundaryDoesNotRecoverPreviousTrip() {
        when(repository.summary("owner", "session")).thenReturn(null);
        assertTrue(recovery.recover(id).isEmpty());
        verify(repository, never()).completedRecent(anyString(), anyLong(), anyInt());
    }

    @Test
    void unknownRoleAndDeletedConversationHaveNoRecovery() {
        assertTrue(recovery.recover("normal:" + MemoryScope.encode("session", 3, 1)).isEmpty());
        when(conversations.selectById("session"))
                .thenReturn(Conversation.builder().userId("owner").isDelete((short) 1).build());
        assertTrue(recovery.recover(id).isEmpty());
        verifyNoInteractions(repository);
    }

    @Test
    void changedFloorDuringRecoveryDiscardsTheOldSnapshot() {
        when(repository.summary("owner", "session"))
                .thenReturn(Summary.empty(3, 4, 10), Summary.empty(3, 5, 20));
        assertTrue(recovery.recover(id).isEmpty());
    }

    @Test
    void truncatedHistoryStartingWithAssistantGetsExplicitBackgroundHeader() {
        when(repository.completedRecent(eq("session"), eq(10L), anyInt()))
                .thenReturn(List.of(new History(12, (short) 2, "上次已完成的方案")));
        var result = recovery.recover(id);
        assertInstanceOf(UserMessage.class, result.get(0));
        assertTrue(((UserMessage) result.get(0)).singleText().contains("背景参考"));
        assertInstanceOf(AiMessage.class, result.get(1));
    }
}
