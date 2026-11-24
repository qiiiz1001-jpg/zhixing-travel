package cn.zhixing.travel.config;

import static org.bsc.langgraph4j.StateGraph.END;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.bsc.langgraph4j.studio.LangGraphStudioServer;
import org.bsc.langgraph4j.studio.springboot.LangGraphStudioConfig;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class LangGraphStudioSampleConfig extends LangGraphStudioConfig {

    public static final class StudioState extends AgentState {
        public static final Map<String, Channel<?>> SCHEMA =
                Map.of(
                        "input", Channels.base(() -> ""),
                        "output", Channels.base(() -> ""));

        public StudioState(Map<String, Object> data) {
            super(data);
        }

        public String input() {
            return this.<String>value("input").orElse("");
        }
    }

    @Override
    public Map<String, LangGraphStudioServer.Instance> instanceMap() {
        AsyncNodeAction<StudioState> echo =
                AsyncNodeAction.node_async(
                        state -> {
                            Map<String, Object> updates = new HashMap<>();
                            updates.put("output", state.input());
                            return updates;
                        });

        StateGraph<StudioState> workflow;
        try {
            workflow =
                    new StateGraph<>(StudioState.SCHEMA, StudioState::new)
                            .addNode("echo", echo)
                            .addEdge(StateGraph.START, "echo")
                            .addEdge("echo", END);
        } catch (GraphStateException e) {
            throw new IllegalStateException(e);
        }

        CompileConfig compileConfig =
                CompileConfig.builder().checkpointSaver(new MemorySaver()).build();

        LangGraphStudioServer.Instance instance =
                LangGraphStudioServer.Instance.builder()
                        .title("LangGraph Studio")
                        .addInputStringArg("input")
                        .graph(workflow)
                        .compileConfig(compileConfig)
                        .build();

        return Map.of("default", instance);
    }
}
