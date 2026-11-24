package cn.zhixing.travel.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Builder;
import lombok.Data;

/** */
@Data
@Builder
@TableName("message")
public class Message {

    @TableId("id")
    private String id;

    @TableField("conversation_id")
    private String conversationId;

    /** 角色 0-system 1-user */
    @TableField("role")
    private Short role;

    @TableField("content")
    private String content;

    @TableField("created_at")
    private String createdAt;

    @TableField("is_delete")
    private Short isDelete;
}
