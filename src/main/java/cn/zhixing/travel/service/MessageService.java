package cn.zhixing.travel.service;

import cn.zhixing.travel.model.Message;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

/** */
public interface MessageService extends IService<Message> {
    Page<Message> pageByConversation(String userId, String conversationId, long page, long size);
}
