package cn.zhixing.travel.agent.transportation.subagents;

import cn.zhixing.travel.agent.requirement.Requirements;

import com.alibaba.dashscope.utils.JsonUtils;

import cn.zhixing.travel.config.LiveTravelQueries;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TrainTransportSubAgent {

    @Autowired private LiveTravelQueries liveQueries;
    @Autowired private TrainTransportSubService trainTransportSubService;

    public String execute(String sessionId, Requirements requirements) {
        if (liveQueries != null) {
            String actual = liveQueries.transport("train", requirements);
            if (actual != null) return actual;
        }
        String prompt = JsonUtils.toJson(requirements);
        return trainTransportSubService.chat(sessionId, prompt);
    }
}
