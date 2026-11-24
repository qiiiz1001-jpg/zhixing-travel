package cn.zhixing.travel.memory;

/** 模型记忆范围可换代，图与 RAG 始终保留原始会话身份。 */
public final class MemoryScope {
    private static final String PREFIX = "travel-memory|";

    private MemoryScope() {}

    public static String encode(String session, long preferenceVersion, long tripGeneration) {
        return PREFIX + session + "|" + preferenceVersion + "|" + tripGeneration;
    }

    public static String sessionId(Object memoryId) {
        String value = String.valueOf(memoryId);
        if (!value.startsWith(PREFIX)) return value;
        String[] parts = value.split("\\|", -1);
        if (parts.length != 4 || parts[1].isBlank()) return "";
        try {
            Long.parseLong(parts[2]);
            Long.parseLong(parts[3]);
        } catch (NumberFormatException error) {
            return "";
        }
        return parts[1];
    }
}
