package cn.zhixing.travel.config;

import cn.zhixing.travel.agent.state.TravelWorkflowState;

import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.SQLException;

/** */
@Configuration
public class CheckpointConfig {
    @Value("${pgcheckpoint.host}")
    private String host;

    @Value("${pgcheckpoint.username}")
    private String username;

    @Value("${pgcheckpoint.password}")
    private String password;

    @Value("${pgcheckpoint.database}")
    private String pgCheckpointDatabase;

    @Value("${pgcheckpoint.port}")
    private Integer port;

    @Bean
    @org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
    public PostgresSaver checkpointSaver() throws SQLException {
        return PostgresSaver.builder()
                .host(host)
                .port(port)
                .database(pgCheckpointDatabase)
                .user(username)
                .password(password)
                .stateSerializer(new ObjectStreamStateSerializer<>(TravelWorkflowState::new))
                .createTables(false) // 建表与索引由幂等 SQL 负责，重启不重复创建索引。
                .build();
    }
}
