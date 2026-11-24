package cn.zhixing.travel.agent.accommodation;

import cn.zhixing.travel.agent.requirement.Requirements;

import cn.zhixing.travel.config.LiveTravelQueries;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AccommodationPlannerAgent {
    @Autowired private LiveTravelQueries liveQueries;
    @Autowired private AccommodationPlannerService accommodationPlannerService;

    public String execute(String sessionId, Requirements requirements) {
        if (liveQueries != null) {
            String actual = liveQueries.hotels(requirements);
            if (actual != null) return actual;
        }
        String prompt = com.alibaba.dashscope.utils.JsonUtils.toJson(requirements);
        return accommodationPlannerService.chat(sessionId, prompt);
    }
}
