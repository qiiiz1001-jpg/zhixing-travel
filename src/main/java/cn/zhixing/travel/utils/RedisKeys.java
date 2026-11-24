package cn.zhixing.travel.utils;

/** */
public class RedisKeys {
    public static final String PREFIX = "wuxi:zhixing:";

    public static final String USER_AUTH_TOKEN_PREFIX = PREFIX + "user:auth:";

    // 7天
    public static final Integer USER_AUTH_TOKEN_TIMEOUT = 60 * 60 * 24 * 7;

    public static final String RAG_CACHE_PREFIX = PREFIX + "rag:cache:";

    public static String getUserAuthToken(String uid) {
        return USER_AUTH_TOKEN_PREFIX + uid;
    }

    public static String getRagCacheKey(String digest) {
        return RAG_CACHE_PREFIX + digest;
    }
}
