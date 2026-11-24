package cn.zhixing.travel.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

@Component
public class ConversationExecutionLocks {
    private final ReentrantLock[] stripes = new ReentrantLock[256];

    public ConversationExecutionLocks() {
        for (int i = 0; i < stripes.length; i++) stripes[i] = new ReentrantLock();
    }

    public ReentrantLock forSession(String id) {
        return stripes[Math.floorMod(id.hashCode(), stripes.length)];
    }
}
