package cn.zhixing.travel.service.impl;

import cn.zhixing.travel.mapper.ConversationMapper;
import cn.zhixing.travel.model.Conversation;
import cn.zhixing.travel.model.dto.ConversationListResp;
import cn.zhixing.travel.service.ConversationService;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** */
@Service
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation>
        implements ConversationService {

    @Override
    public List<ConversationListResp> listByUid(String userId) {
        List<Conversation> res =
                this.lambdaQuery()
                        .eq(Conversation::getUserId, userId)
                        .eq(Conversation::getIsDelete, 0)
                        .orderByDesc(Conversation::getUpdatedAt)
                        .list();

        if (res == null) {
            return new ArrayList<>();
        }

        return res.stream()
                .map(
                        c -> {
                            ConversationListResp resp = new ConversationListResp();
                            resp.setId(c.getId());
                            resp.setTitle(c.getTitle());
                            return resp;
                        })
                .toList();
    }

    @Override
    public ConversationListResp create(String userId) {
        Conversation conversation = Conversation.builder().build();
        conversation.setId(UUID.randomUUID().toString());
        conversation.setUserId(userId);
        this.save(conversation);
        return new ConversationListResp(conversation.getId(), conversation.getTitle());
    }
}
