package cn.zhixing.travel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.zhixing.travel.exception.BusinessException;
import cn.zhixing.travel.mapper.ConversationMapper;
import cn.zhixing.travel.model.Conversation;
import cn.zhixing.travel.service.ConversationExecutionLocks;
import cn.zhixing.travel.service.impl.ChatServiceImpl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

class ConversationOwnershipTest {
    @Test
    void rejectsAnotherUsersConversationBeforeGraphOrMessageWrite() {
        var mapper = mock(ConversationMapper.class);
        var conversation = Conversation.builder().build();
        conversation.setUserId("owner");
        conversation.setIsDelete((short) 0);
        String id = UUID.randomUUID().toString();
        when(mapper.selectById(id)).thenReturn(conversation);
        var service = new ChatServiceImpl();
        ReflectionTestUtils.setField(service, "conversationMapper", mapper);
        ReflectionTestUtils.setField(service, "executionLocks", new ConversationExecutionLocks());
        assertThrows(
                BusinessException.class,
                () -> service.chat("other", id, "hello").collectList().block());
        verify(mapper, never()).insert(any(Conversation.class));
    }
}
