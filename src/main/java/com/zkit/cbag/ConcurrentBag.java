package com.zkit.cbag;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.zkit.concurrentbag.IConcurrentBagEntry.STATE_IN_USE;
import static com.zkit.concurrentbag.IConcurrentBagEntry.STATE_NOT_IN_USE;

// 核心概念：状态机 + 基础CAS
public class ConcurrentBag<T extends IConcurrentBagEntry> {
    private final List<T> sharedList = new CopyOnWriteArrayList<>();
    
    public void add(T entry) {
        sharedList.add(entry);
    }
    
    public T borrow() {
        // 简单遍历查找
        for (T entry : sharedList) {
            if (entry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                return entry;
            }
        }
        return null; // 没有可用对象
    }
    
    public void requite(T entry) {
        entry.setState(STATE_NOT_IN_USE);
    }
}