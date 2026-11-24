package cn.zhixing.travel;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = RedisEmbeddingStoreAutoConfiguration.class)
@MapperScan("cn.zhixing.travel.mapper")
@EnableScheduling
public class ZhiXingTravelApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZhiXingTravelApplication.class, args);
    }
}
