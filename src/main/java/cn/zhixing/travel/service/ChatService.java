package cn.zhixing.travel.service;

import reactor.core.publisher.Flux;

/** */
public interface ChatService {
    Flux<String> chat(String userId, String sessionId, String prompt);
}
