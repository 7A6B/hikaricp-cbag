package com.zkit.cbag;

import org.junit.Before;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ConcurrentBagTest {

	private ConcurrentBag<BagEntry> bag;

	@Before
	public void setUp() {
		bag = new ConcurrentBag<>(waiting -> {
		});
	}

	// 测试基本的添加和借用功能
	@Test
	public void testAddAndBorrow() throws InterruptedException {
		BagEntry entry = new BagEntry();
		bag.add(entry);

		BagEntry borrowed = bag.borrow(0, TimeUnit.MILLISECONDS);
		assertNotNull("应该能借到一个元素", borrowed);
		assertEquals("借到的应该是同一个元素", entry, borrowed);
		assertEquals("元素状态应该是IN_USE", IConcurrentBagEntry.STATE_IN_USE, borrowed.getState());

		// 再次尝试借用，应该返回null因为没有可用元素了
		BagEntry secondBorrow = bag.borrow(0, TimeUnit.MILLISECONDS);
		assertNull("不应该能借到第二个元素", secondBorrow);
	}

	// 测试归还功能
	@Test
	public void testRequite() throws InterruptedException {
		BagEntry entry = new BagEntry();
		bag.add(entry);

		BagEntry borrowed = bag.borrow(0, TimeUnit.MILLISECONDS);
		bag.requite(borrowed);

		assertEquals("归还后状态应该是NOT_IN_USE", IConcurrentBagEntry.STATE_NOT_IN_USE, borrowed.getState());

		// 归还后应该可以再次借用
		BagEntry secondBorrow = bag.borrow(0, TimeUnit.MILLISECONDS);
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
					BagEntry entry = bag.borrow(0, TimeUnit.MILLISECONDS);
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

	//------------------stage2 test case-------------------------
	// 测试borrow方法的超时功能
	@Test
	public void testBorrowTimeout() throws InterruptedException {
		// 不添加任何元素到bag中

		// 尝试借用元素，应该等待指定时间后返回null
		long startTime = System.currentTimeMillis();
		BagEntry entry = bag.borrow(100, TimeUnit.MILLISECONDS);
		long endTime = System.currentTimeMillis();

		assertNull("应该返回null因为没有可用元素", entry);
		assertTrue("应该至少等待了指定的超时时间", (endTime - startTime) >= 100);
	}

	// 测试borrow方法的等待和通知机制
	@Test
	public void testBorrowWaitAndNotify() throws InterruptedException {
		// 创建一个记录addBagItem调用次数的监听器
		final AtomicInteger addItemCalls = new AtomicInteger(0);
		ConcurrentBag<BagEntry> testBag = new ConcurrentBag<>((waiting) -> {
			addItemCalls.incrementAndGet();
		});

		// 在另一个线程中延迟添加元素
		Thread adderThread = new Thread(() -> {
			try {
				Thread.sleep(50); // 延迟50毫秒
				testBag.add(new BagEntry());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		// 启动添加线程
		adderThread.start();

		// 尝试借用元素，应该会等待直到元素被添加
		long startTime = System.currentTimeMillis();
		BagEntry entry = testBag.borrow(200, TimeUnit.MILLISECONDS);
		long endTime = System.currentTimeMillis();

		assertNotNull("应该能借到添加的元素", entry);
		assertTrue("应该等待了一段时间", (endTime - startTime) >= 50);
		assertTrue("应该等待时间少于超时时间", (endTime - startTime) < 200);
		assertEquals("应该调用了addBagItem方法", 1, addItemCalls.get());
	}

	// 测试borrow方法在高竞争情况下的性能
	@Test
	public void testBorrowUnderHighContention() throws InterruptedException {
		// 添加少量元素
		final int entryCount = 5;
		for (int i = 0; i < entryCount; i++) {
			bag.add(new BagEntry());
		}

		// 创建大量线程同时尝试借用
		final int threadCount = 100;
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch completeLatch = new CountDownLatch(threadCount);
		final AtomicInteger successCount = new AtomicInteger(0);
		final AtomicInteger timeoutCount = new AtomicInteger(0);

		for (int i = 0; i < threadCount; i++) {
			new Thread(() -> {
				try {
					startLatch.await(); // 等待同时开始
					BagEntry entry = bag.borrow(50, TimeUnit.MILLISECONDS);
					if (entry != null) {
						successCount.incrementAndGet();
					} else {
						timeoutCount.incrementAndGet();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					completeLatch.countDown();
				}
			}).start();
		}

		startLatch.countDown(); // 所有线程同时开始
		completeLatch.await(); // 等待所有线程完成

		assertEquals("成功借用的数量应该等于元素数量", entryCount, successCount.get());
		assertEquals("超时的数量应该是线程总数减去成功数", threadCount - entryCount, timeoutCount.get());
	}

	// 测试borrow方法的waiters计数器功能
	@Test
	public void testBorrowWaitersCounter() throws InterruptedException {
		// 创建一个捕获waiting参数的监听器
		final List<Integer> waitingValues = new ArrayList<>();
		ConcurrentBag<BagEntry> testBag = new ConcurrentBag<>((waiting) -> {
			synchronized (waitingValues) {
				waitingValues.add(waiting);
			}
		});

		// 创建多个线程同时等待
		final int threadCount = 5;
		final CountDownLatch startLatch = new CountDownLatch(1);
		final CountDownLatch completeLatch = new CountDownLatch(threadCount);

		for (int i = 0; i < threadCount; i++) {
			new Thread(() -> {
				try {
					startLatch.await(); // 等待统一开始
					testBag.borrow(100, TimeUnit.MILLISECONDS); // 会超时
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					completeLatch.countDown();
				}
			}).start();
		}

		startLatch.countDown(); // 所有线程同时开始
		completeLatch.await(); // 等待所有线程完成

		// 验证waiters计数器的值
		assertEquals("应该有5次addBagItem调用", threadCount, waitingValues.size());

		// 验证所有waiting值都大于0
		for (int value : waitingValues) {
			assertTrue("waiting值应该大于0", value > 0);
		}
		
		// 验证最终值应该等于线程数
		assertEquals("最大的waiting值应该等于线程数", threadCount, Collections.max(waitingValues).intValue());
	}

	//简单参考
	@Test
	public void testBorrowWaitingEfficiency() throws InterruptedException {
		// 创建一个空的bag
		ConcurrentBag<BagEntry> testBag = new ConcurrentBag<>((waiting) -> {});

		// 记录CPU时间
		long startCpuTime = getThreadCpuTime(Thread.currentThread().getId());

		// 尝试借用元素，会触发等待
		testBag.borrow(100, TimeUnit.MILLISECONDS);

		// 计算消耗的CPU时间
		long cpuTimeUsed = getThreadCpuTime(Thread.currentThread().getId()) - startCpuTime;

		// 验证CPU使用率不超过某个阈值（例如20%）
		long totalTime = TimeUnit.MILLISECONDS.toNanos(100);
		double cpuUsageRatio = (double)cpuTimeUsed / totalTime;

		assertTrue("等待期间CPU使用率应该较低", cpuUsageRatio < 0.2);
	}

	// 获取线程CPU时间的辅助方法
	private long getThreadCpuTime(long threadId) {
		ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
		if (threadMXBean.isThreadCpuTimeSupported() && threadMXBean.isThreadCpuTimeEnabled()) {
			return threadMXBean.getThreadCpuTime(threadId);
		}
		return 0L; // 如果不支持，返回0
	}
}