package cn.zhixing.travel.agent.transportation.subagents;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface FlightTransportSubService {
    @SystemMessage(fromResource = "docs/sys_agent/Flight.txt")
    String chat(@MemoryId String sessionId, @UserMessage String prompt);
}
