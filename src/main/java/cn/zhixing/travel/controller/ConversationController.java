package cn.zhixing.travel.controller;

import cn.zhixing.travel.agent.requirement.TripCities;
import cn.zhixing.travel.mapper.StoreMapper;
import cn.zhixing.travel.model.Message;
import cn.zhixing.travel.model.dto.ApiResult;
import cn.zhixing.travel.model.dto.ConversationListResp;
import cn.zhixing.travel.service.ConversationService;
import cn.zhixing.travel.service.MessageService;
import cn.zhixing.travel.utils.RequestContext;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/conversation")
public class ConversationController {

    @Autowired private ConversationService conversationService;

    @Autowired private MessageService messageService;
    @Autowired private StoreMapper storeMapper;
    @Autowired private ObjectMapper objectMapper;

    @GetMapping("/list")
    public ApiResult<List<ConversationListResp>> list() {
        String userId = RequestContext.getUserId();
        List<ConversationListResp> res = conversationService.listByUid(userId);
        return ApiResult.success(res);
    }

    @PostMapping("/create")
    public ApiResult<ConversationListResp> create() {
        String userId = RequestContext.getUserId();
        ConversationListResp res = conversationService.create(userId);
        return ApiResult.success(res);
    }

    @GetMapping("/messages")
    public ApiResult<Page<Message>> messages(
            @RequestParam("conversationId") String conversationId,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "50") long size) {
        String userId = RequestContext.getUserId();
        return ApiResult.success(
                messageService.pageByConversation(userId, conversationId, page, size));
    }

    @GetMapping("/plan")
    public ApiResult<JsonNode> latestPlan(@RequestParam String conversationId)
            throws JsonProcessingException {
        String plan = storeMapper.latestPlan(conversationId, RequestContext.getUserId());
        if (plan == null) return ApiResult.success(null);
        JsonNode saved = objectMapper.readTree(plan);
        JsonNode locations = saved.path("requirements");
        // 旧版本可能已把省份当作城市发布，避免继续显示那次错城查询的结果。
        if (TripCities.provinceOnly(locations.path("origin").asText())
                || TripCities.provinceOnly(locations.path("destination").asText()))
            return ApiResult.success(null);
        return ApiResult.success(saved);
    }
}
