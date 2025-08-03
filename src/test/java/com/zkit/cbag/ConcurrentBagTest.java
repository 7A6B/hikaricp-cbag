package com.zkit.cbag;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ConcurrentBagTest {

    private ConcurrentBag<BagEntry> bag;
    
    @Before
    public void setUp() {
        bag = new ConcurrentBag<>();
    }
    
    // 测试基本的添加和借用功能
    @Test
    public void testAddAndBorrow() {
        BagEntry entry = new BagEntry();
        bag.add(entry);
        
        BagEntry borrowed = bag.borrow();
        assertNotNull("应该能借到一个元素", borrowed);
        assertEquals("借到的应该是同一个元素", entry, borrowed);
        assertEquals("元素状态应该是IN_USE", IConcurrentBagEntry.STATE_IN_USE, borrowed.getState());
        
        // 再次尝试借用，应该返回null因为没有可用元素了
        BagEntry secondBorrow = bag.borrow();
        assertNull("不应该能借到第二个元素", secondBorrow);
    }
    
    // 测试归还功能
    @Test
    public void testRequite() {
        BagEntry entry = new BagEntry();
        bag.add(entry);
        
        BagEntry borrowed = bag.borrow();
        bag.requite(borrowed);
        
        assertEquals("归还后状态应该是NOT_IN_USE", IConcurrentBagEntry.STATE_NOT_IN_USE, borrowed.getState());
        
        // 归还后应该可以再次借用
        BagEntry secondBorrow = bag.borrow();
        assertNotNull("归还后应该能再次借用", secondBorrow);
        assertEquals("应该借到同一个元素", entry, secondBorrow);
    }
    
    // 测试多线程并发借用
    @Test
    public void testConcurrentBorrow() throws InterruptedException {
        // 添加10个元素到bag中
        for (int i = 0; i < 10; i++) {
            bag.add(new BagEntry());
        }
        
        final int threadCount = 20; // 使用比元素多的线程数
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch completeLatch = new CountDownLatch(threadCount);
        final AtomicInteger successfulBorrows = new AtomicInteger(0);
        final List<BagEntry> borrowedEntries = new ArrayList<>();
        
        // 创建多个线程同时尝试借用元素
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    latch.await(); // 等待统一开始
                    BagEntry entry = bag.borrow();
                    if (entry != null) {
                        synchronized (borrowedEntries) {
                            borrowedEntries.add(entry);
                        }
                        successfulBorrows.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            }).start();
        }
        
        latch.countDown(); // 所有线程同时开始
        completeLatch.await(); // 等待所有线程完成
        
        // 验证结果
        assertEquals("应该只有10个线程成功借用", 10, successfulBorrows.get());
        assertEquals("借用的元素数量应该是10", 10, borrowedEntries.size());
        
        // 验证没有重复借用同一个元素
       Set<BagEntry> uniqueEntries = new HashSet<>(borrowedEntries);
        assertEquals("不应该有重复借用的元素", borrowedEntries.size(), uniqueEntries.size());
        // 验证没有重复借用同一个元素 写法二
        long uniqueCount = borrowedEntries.stream().distinct().count();
        assertEquals("不应该有重复借用的元素", borrowedEntries.size(), uniqueCount);
        // 验证所有借用的元素状态都是IN_USE
        for (BagEntry entry : borrowedEntries) {
            assertEquals("借用的元素状态应该是IN_USE", IConcurrentBagEntry.STATE_IN_USE, entry.getState());
        }
    }
    
    // 测试CAS操作的原子性
    @Test
    public void testCASAtomicity() {
        BagEntry entry = new BagEntry();
        bag.add(entry);
        
        // 第一次CAS应该成功
        boolean firstCAS = entry.compareAndSet(IConcurrentBagEntry.STATE_NOT_IN_USE, IConcurrentBagEntry.STATE_IN_USE);
        assertTrue("第一次CAS应该成功", firstCAS);
        
        // 第二次相同的CAS应该失败，因为状态已经改变
        boolean secondCAS = entry.compareAndSet(IConcurrentBagEntry.STATE_NOT_IN_USE, IConcurrentBagEntry.STATE_IN_USE);
        assertFalse("第二次CAS应该失败", secondCAS);
        
        // 使用正确的期望状态，CAS应该成功
        boolean thirdCAS = entry.compareAndSet(IConcurrentBagEntry.STATE_IN_USE, IConcurrentBagEntry.STATE_NOT_IN_USE);
        assertTrue("使用正确的期望状态CAS应该成功", thirdCAS);
    }
    
}