package cn.zhixing.travel.mapper;

import org.apache.ibatis.annotations.Param;

public interface StoreMapper {

    String latestPlan(@Param("sessionId") String sessionId, @Param("userId") String userId);

    int upsert(
            @Param("namespace") String[] namespace,
            @Param("key") String key,
            @Param("jsonValue") String jsonValue);
}
