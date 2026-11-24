package cn.zhixing.travel.memory;

import cn.zhixing.travel.mapper.ConversationMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Redis 角色窗口缺失时，从 SQL 恢复有限的业务上下文，不伪造工具调用轨迹。 */
@Component
public class PlanningHistoryRecovery {
    private static final List<String> ROLES =
            List.of(
                    "requirement",
                    "transport",
                    "accommodation",
                    "food",
                    "driving",
                    "train",
                    "flight");
    private final ConversationMapper conversations;
    private final MemoryRepository repository;

    @Value("${memory.recent.max-messages:20}")
    private int maxMessages = 20;

    @Value("${memory.recent.max-message-chars:6000}")
    private int maxMessageChars = 6000;

    public PlanningHistoryRecovery(ConversationMapper conversations, MemoryRepository repository) {
        this.conversations = conversations;
        this.repository = repository;
    }

    public List<ChatMessage> recover(Object memoryId) {
        String key = String.valueOf(memoryId);
        int colon = key.indexOf(':');
        if (colon < 0 || !ROLES.contains(key.substring(0, colon))) return List.of();
        String encoded = key.substring(colon + 1);
        String[] scope = encoded.split("\\|", -1);
        if (scope.length != 4 || !"travel-memory".equals(scope[0])) return List.of();
        String session = MemoryScope.sessionId(encoded);
        if (session.isBlank()) return List.of();
        long version = Long.parseLong(scope[2]);
        long generation = Long.parseLong(scope[3]);
        if (version < 0) return List.of(); // 偏好读取失败时不从旧对话恢复个性化条件。
        var conversation = conversations.selectById(session);
        if (conversation == null || !Short.valueOf((short) 0).equals(conversation.getIsDelete()))
            return List.of();
        String owner = conversation.getUserId();
        if (repository.profile(owner).version() != version) return List.of();
        var summary = repository.summary(owner, session);
        if (summary != null && summary.contextVersion() != version) return List.of();
        // 新范围若缺少可信边界，宁可使用当前 State，也不回放变更前的原话。
        if (summary == null && (version > 0 || generation > 0)) return List.of();
        long floor = summary == null ? 0 : summary.floorSequence();
        int limit = Math.max(0, maxMessages - 3); // 预留系统消息、本轮输入及可能的背景说明。
        if (limit == 0) return List.of();
        var history = repository.completedRecent(session, floor, limit);
        if (history.isEmpty()) return List.of();
        List<ChatMessage> messages = new ArrayList<>();
        if (history.get(0).role() != 1) {
            messages.add(UserMessage.from("以下是从 SQL 恢复的历史业务回复，仅供背景参考；当前结构化需求和最新输入优先。"));
        }
        for (var message : history) {
            String content =
                    TravelMemoryService.clip(message.content(), Math.min(1200, maxMessageChars));
            if (content.isBlank()) continue;
            messages.add(message.role() == 1 ? UserMessage.from(content) : AiMessage.from(content));
        }
        // 加载过程中偏好或摘要边界已变化，不能发布旧背景。
        if (repository.profile(owner).version() != version) return List.of();
        var latest = repository.summary(owner, session);
        if (summary == null
                ? latest != null
                : latest == null
                        || latest.contextVersion() != version
                        || latest.floorSequence() != floor) return List.of();
        return messages;
    }
}
