package cn.zhixing.travel.service;

import cn.zhixing.travel.model.dto.ConversationListResp;

import java.util.List;

/** */
public interface ConversationService {

    List<ConversationListResp> listByUid(String userId);

    ConversationListResp create(String userId);
}
