package com.zkit.cbag;

import java.util.concurrent.atomic.AtomicInteger;

public class BagEntry implements IConcurrentBagEntry {
   private final AtomicInteger state = new AtomicInteger(STATE_NOT_IN_USE);
   private final int id; // 添加唯一标识
   private static final AtomicInteger SEQUENCE = new AtomicInteger(1000);

   public BagEntry() {
      this.id = SEQUENCE.incrementAndGet();
   }

   @Override
   public boolean compareAndSet(int expect, int update) {
      return state.compareAndSet(expect, update);
   }

   @Override
   public void setState(int newState) {
      state.set(newState);
   }

   @Override
   public int getState() {
      return state.get();
   }

   public int getId() {
      return id;
   }

   @Override
   public String toString() {
      return "TestBagEntry-" + id + "(state=" + state.get() + ")";
   }
}
