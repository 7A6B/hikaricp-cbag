package com.zkit.cbag;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zkit.cbag.IConcurrentBagEntry.STATE_IN_USE;
import static com.zkit.cbag.IConcurrentBagEntry.STATE_NOT_IN_USE;

// 核心概念：状态机 + 基础CAS
public class ConcurrentBag<T extends IConcurrentBagEntry> {
    private final List<T> sharedList = new CopyOnWriteArrayList<>();
    private final AtomicInteger waiters = new AtomicInteger(); // 新增：等待计数
    private final IBagStateListener listener;

    public ConcurrentBag(IBagStateListener listener) {
        this.listener = listener;
    }


    public void add(T entry) {
        sharedList.add(entry);
    }

    public T borrow(long timeout, TimeUnit timeUnit) throws InterruptedException {
        // 先尝试快速获取
        for (T entry : sharedList) {
            if (entry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                return entry;
            }
        }

        // 没有可用对象，进入等待
        waiters.incrementAndGet();
        try {
            listener.addBagItem(waiters.get()); // 通知需要创建对象

            // 简单轮询等待（教学用，后续会优化）
            long deadline = System.nanoTime() + timeUnit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                for (T entry : sharedList) {
                    if (entry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                        return entry;
                    }
                }
                TimeUnit.MILLISECONDS.sleep(1); // 简单等待
            }
            return null; // 超时
        } finally {
            waiters.decrementAndGet();
        }
    }
    public void requite(T entry) {
        entry.setState(STATE_NOT_IN_USE);
    }
}