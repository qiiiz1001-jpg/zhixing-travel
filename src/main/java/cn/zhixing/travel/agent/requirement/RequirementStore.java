package cn.zhixing.travel.agent.requirement;

import com.alibaba.dashscope.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import redis.clients.jedis.JedisPooled;

import java.util.Map;

@Slf4j
@Component
public class RequirementStore {

    private final JedisPooled jedisPooled;
    private final ObjectMapper objectMapper;
    TypeReference<Map<String, String>> mapType = new TypeReference<>() {};

    public RequirementStore(JedisPooled jedisPooled, ObjectMapper objectMapper) {
        this.jedisPooled = jedisPooled;
        this.objectMapper = objectMapper;
    }

    public String load(String key) {
        return jedisPooled.get(key);
    }

    public Map<String, String> loadMap(String sessionId) {
        String key = getKey(sessionId);
        String json = jedisPooled.get(key);
        JsonUtils.fromJson(json, mapType.getType());
        try {
            return objectMapper.readValue(json, mapType);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public void save(String sessionId, Map<String, String> data) {
        String json = JsonUtils.toJson(data);
        jedisPooled.set(getKey(sessionId), json);
    }

    public static String getKey(String sessionId) {
        return "travel:sessionId:" + sessionId;
    }
}
