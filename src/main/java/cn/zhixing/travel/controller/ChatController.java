package cn.zhixing.travel.controller;

import cn.zhixing.travel.agent.NormalService;
import cn.zhixing.travel.model.dto.ChatRequest;
import cn.zhixing.travel.service.ChatService;
import cn.zhixing.travel.utils.RequestContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/travel")
public class ChatController {

    @Autowired private NormalService normalService;

    @Autowired private ChatService chatService;

    @Autowired private cn.zhixing.travel.mapper.ConversationMapper conversations;

    /** 普通对话测试 */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> chat(@jakarta.validation.Valid @RequestBody ChatRequest chatRequest) {
        String userId = RequestContext.getUserId();
        return chatService.chat(userId, chatRequest.getSessionId(), chatRequest.getPrompt());
    }

    @PostMapping(value = "/stream/chat", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> streamChat(@jakarta.validation.Valid @RequestBody ChatRequest chatRequest) {
        return chat(chatRequest);
    }

    @PostMapping(value = "/normal/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> normalStreamChat(
            @jakarta.validation.Valid @RequestBody ChatRequest chatRequest) {
        var conversation = conversations.selectById(chatRequest.getSessionId());
        if (conversation == null
                || !RequestContext.getUserId().equals(conversation.getUserId())
                || conversation.getIsDelete() != 0) {
            throw new cn.zhixing.travel.exception.BusinessException(403, "无权访问此会话");
        }
        return normalService.chat(chatRequest.getSessionId(), chatRequest.getPrompt());
    }
}
