package cn.zhixing.travel.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConversationListResp {

    // 会话id
    private String id;

    private String title;
}
