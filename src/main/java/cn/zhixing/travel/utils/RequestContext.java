package cn.zhixing.travel.utils;

/** 当前 HTTP 请求的认证上下文。 */
public class RequestContext {

    private static final ThreadLocal<RequestContextInfo> threadLocal = new ThreadLocal<>();

    public static void set(RequestContextInfo requestHolderInfo) {
        threadLocal.set(requestHolderInfo);
    }

    public static RequestContextInfo get() {
        return threadLocal.get();
    }

    public static String getUserId() {
        return threadLocal.get().getUserId();
    }

    public static void remove() {
        threadLocal.remove();
    }
}
