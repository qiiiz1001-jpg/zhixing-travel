package cn.zhixing.travel.service.impl;

import cn.zhixing.travel.agent.requirement.RequirementAgent;
import cn.zhixing.travel.agent.requirement.RequirementChanges;
import cn.zhixing.travel.agent.state.TravelWorkflowState;
import cn.zhixing.travel.mapper.ConversationMapper;
import cn.zhixing.travel.mapper.StoreMapper;
import cn.zhixing.travel.model.Conversation;
import cn.zhixing.travel.model.Message;
import cn.zhixing.travel.service.ChatService;
import cn.zhixing.travel.service.MessageService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.StringJoiner;

@Service
public class ChatServiceImpl implements ChatService {

    @Autowired private RequirementAgent requirementAgent;

    @Autowired private ConversationMapper conversationMapper;

    @Autowired private MessageService messageService;

    @Autowired private StoreMapper storeMapper;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private cn.zhixing.travel.knowledge.KnowledgeRepository knowledge;

    @Autowired private cn.zhixing.travel.memory.TravelMemoryService memory;

    @Autowired private cn.zhixing.travel.service.ConversationExecutionLocks executionLocks;

    @Override
    public Flux<String> chat(String userId, String sessionId, String prompt) {
        return Mono.fromCallable(
                        () -> {
                            var lock = executionLocks.forSession(sessionId);
                            lock.lock();
                            try {
                                ensureConversationExists(userId, sessionId);
                                if (prompt == null || prompt.isBlank() || prompt.length() > 8000)
                                    throw new cn.zhixing.travel.exception.BusinessException(
                                            400, "每条消息请控制在 8000 字以内");
                                var context = memory.prepare(userId, sessionId);
                                context.put(
                                        "memoryOperationId",
                                        insertMessage(sessionId, (short) 1, prompt));
                                var state =
                                        requirementAgent.runState(
                                                sessionId,
                                                prompt,
                                                knowledge.revision(userId),
                                                context);
                                String output = state.output();
                                if (output == null || output.isBlank())
                                    throw new IllegalStateException("旅行规划结果为空");
                                boolean plan =
                                        "PLAN"
                                                .equals(
                                                        state.<String>value("responseType")
                                                                .orElse(""));
                                String reply = plan ? planReply(state) : output;
                                if (plan) {
                                    memory.publishPlan(
                                            state,
                                            () -> persistReply(sessionId, state, reply, true));
                                } else {
                                    persistReply(sessionId, state, reply, false);
                                }
                                memory.summarize(userId, sessionId, state.requirements());
                                return reply;
                            } finally {
                                lock.unlock();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::just);
    }

    private String planReply(TravelWorkflowState state) {
        String intent = state.<String>value("intent").orElse("NEW");
        if ("NEW".equals(intent) || !state.value("hadPreviousPlan", false)) return state.output();
        var updates = new StringJoiner("\n\n");
        if (state.affects(RequirementChanges.TRANSPORT))
            updates.add("【交通方案更新】\n" + state.transportOutput().strip());
        if (state.affects(RequirementChanges.ACCOMMODATION))
            updates.add("【住宿方案更新】\n" + state.accommodationOutput().strip());
        if (state.affects(RequirementChanges.FOOD))
            updates.add(
                    "【美食方案更新】\n"
                            + state.<String>value("foodReply")
                                    .filter(reply -> !reply.isBlank())
                                    .orElseGet(state::foodOutput)
                                    .strip());
        String notice = state.<String>value("memoryNotice").orElse("");
        return notice + (updates.length() == 0 ? "已保留当前行程方案，可在右侧查看。" : updates.toString());
    }

    private void persistReply(
            String sessionId, TravelWorkflowState state, String reply, boolean completedPlan) {
        insertMessage(sessionId, (short) 2, reply);
        updateConversationUpdatedAt(sessionId);
        if (!completedPlan) return;
        java.util.Map<String, Object> value = new java.util.HashMap<>();
        value.put("requirements", state.requirements());
        value.put("effectiveRequirements", state.effectiveRequirements());
        value.put("transportOutput", state.transportOutput());
        value.put("accommodationOutput", state.accommodationOutput());
        value.put("foodOutput", state.foodOutput());
        value.put("output", state.output());
        try {
            storeMapper.upsert(
                    new String[] {"conversations", sessionId},
                    "latest_plan",
                    objectMapper.writeValueAsString(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("方案序列化失败", error);
        }
    }

    private void ensureConversationExists(String userId, String conversationId) {
        if (userId == null || sessionIdInvalid(conversationId)) {
            throw new IllegalArgumentException("用户或会话参数无效");
        }
        Conversation existing = conversationMapper.selectById(conversationId);
        if (existing != null) {
            if (!userId.equals(existing.getUserId()) || existing.getIsDelete() != 0) {
                throw new cn.zhixing.travel.exception.BusinessException(403, "无权访问此会话");
            }
            return;
        }

        Conversation conversation = Conversation.builder().build();
        conversation.setId(conversationId);
        conversation.setUserId(userId);
        conversation.setTitle("新的会话");
        conversation.setCreatedAt(java.time.OffsetDateTime.now());
        conversation.setUpdatedAt(java.time.OffsetDateTime.now());
        conversation.setIsDelete((short) 0);
        conversationMapper.insert(conversation);
    }

    private boolean sessionIdInvalid(String id) {
        if (id == null) return true;
        try {
            return !java.util.UUID.fromString(id).toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException error) {
            return true;
        }
    }

    private void updateConversationUpdatedAt(String conversationId) {
        Conversation conversation = Conversation.builder().build();
        conversation.setId(conversationId);
        conversation.setUpdatedAt(java.time.OffsetDateTime.now());
        conversationMapper.updateById(conversation);
    }

    private String insertMessage(String conversationId, short role, String content) {
        Message message = Message.builder().build();
        message.setId(java.util.UUID.randomUUID().toString());
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setIsDelete((short) 0);
        messageService.save(message);
        return message.getId();
    }
}
