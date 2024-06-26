package org.bbottema.genericobjectpool;

import org.assertj.core.api.Assertions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.bbottema.genericobjectpool.ObjectPoolTestHelper.createAllocator;

public class SimpleSingleObjectPoolTest {

	private GenericObjectPool<String> pool1, pool2;
	
	@BeforeEach
	public void setup() {
		pool1 = new GenericObjectPool<>(PoolConfig.<String>builder().maxPoolsize(1).build(), createAllocator("a"));
		pool2 = new GenericObjectPool<>(PoolConfig.<String>builder().maxPoolsize(1).build(), createAllocator("b"));
	}
	
	@Test
	public void waitForObjectWithTimeoutTest() throws Exception {
		singleClaimAndRelease();

		PoolableObject<String> obj = pool2.claim();
		try {
			ExecutorService es = Executors.newSingleThreadExecutor();
			Future<?> f = es.submit(new Runnable() {
				public void run() {
					try {
						PoolableObject<String> obj = pool2.claim(500, TimeUnit.MILLISECONDS);
						if (obj == null) {
							throw new TimeoutException();
						}
						obj.release();
						fail("Object was obtained");
					} catch (Exception e) {
						if (!(e instanceof TimeoutException)) {
							fail(e.getMessage(), e);
						}
					}
				}
				
			});
			
			obj.release();
			assertThat(pool2.claim()).isNotNull();
			
			// block until thread is done
			f.get();
		} finally {
			obj.release();
		}
	}
	
	@Test
	public void manyThreadsBlockingUntilObtainedPool2() throws Exception {
		waitForObjectWithTimeoutTest();
		
		Runnable r = new Runnable() {
			public void run() {
				PoolableObject<String> obj = null;
				try {
					obj = pool2.claim();
					assertThat(obj).isNotNull();
					Thread.sleep(20);
					assertThat(pool2.claim()).isNotNull();
				} catch (Exception e) {
					fail("Failed to obtain Object: " + Thread.currentThread().getName(), e);
				} finally {
					if (obj != null)
						obj.release();
				}
			}
		};
		ExecutorService es = Executors.newFixedThreadPool(10);
		for (int i = 0; i < 10; i++)
			es.submit(r);
		es.shutdown();
		es.awaitTermination(2, TimeUnit.SECONDS);
		verifyPool1RemainsUnaffected();
	}
	
	@Test
	public void singleClaimAndRelease() throws Exception {
		PoolableObject<String> obj = pool2.claim();
		assertThat(obj).isNotNull();
		pool2.releasePoolableObject(obj);
		verifyPool1RemainsUnaffected();
	}
	
	private void verifyPool1RemainsUnaffected() {
		PoolMetrics metrics = pool1.getPoolMetrics();
		assertThat(metrics).isNotNull();
		assertThat(metrics.getCurrentlyClaimed()).isZero();
		assertThat(metrics.getCurrentlyWaitingCount()).isZero();
		assertThat(metrics.getTotalAllocated()).isZero();
		assertThat(metrics.getTotalClaimed()).isZero();
	}
	
	@Test
	public void verifyPool1RemainsUnaffectedAfterClaimingPool2() throws Exception {
		manyThreadsBlockingUntilObtainedPool2();
		verifyPool1RemainsUnaffected();
	}
	
	/**
	 * Tests shutting down the pool and not allowing any more allocations
	 */
	@Test
	public void testShutdown() throws Exception {
		verifyPool1RemainsUnaffectedAfterClaimingPool2();
		
		pool1.shutdown();
		pool2.shutdown();

		assertThatThrownBy(pool2::claim).isInstanceOf(IllegalStateException.class);
	}
	
	/**
	 * Tests the Allocator Lifecycle to insure the pool is calling allocator during each phase within the objects lifecycle
	 */
	@Test
	public void testObjectLifecycle() throws Exception {
		TestLifecycleAllocator allocator = new TestLifecycleAllocator();
		GenericObjectPool<Boolean> pool = new GenericObjectPool<>(PoolConfig.<Boolean>builder().maxPoolsize(1).build(), allocator);
		
		assertStatistics(pool, allocator);
	}
	
	/**
	 * Deallocation throws an error, but that should not prevent the deallocation cycle from breaking, causing a memory leak to boot.
	 */
	@Test
	public void testThreadLeakFixGitHub() throws Exception {
		TestLifecycleAllocator allocator = new TestLifecycleAllocator() {
			@Override
			public void deallocate(Boolean object) {
				super.deallocate(object);
				throw new RuntimeException("deallocation fail");
			}
		};
		GenericObjectPool<Boolean> pool = new GenericObjectPool<>(PoolConfig.<Boolean>builder().maxPoolsize(1).build(), allocator);
		
		assertStatistics(pool, allocator);
	}
	
	private void assertStatistics(GenericObjectPool<Boolean> pool, TestLifecycleAllocator allocator) throws InterruptedException {
		PoolableObject<Boolean> obj = pool.claim();
		obj.release();
		obj = pool.claim();
		obj.invalidate();
		
		assertThat(allocator.lifecycleCount).isEqualTo(3);
		TimeUnit.MILLISECONDS.sleep(100);
		assertThat(allocator.lifecycleCount).isEqualTo(4);
		
		assertThat(pool.getPoolMetrics().getCurrentlyAllocated()).isEqualTo(0);
		
		obj = pool.claim();
		obj.release();
		obj = pool.claim();
		obj.invalidate();
		
		assertThat(allocator.lifecycleCount).isEqualTo(7);
		TimeUnit.MILLISECONDS.sleep(100);
		assertThat(allocator.lifecycleCount).isEqualTo(8);
		
		assertThat(pool.getPoolMetrics().getCurrentlyAllocated()).isEqualTo(0);
	}
	
	static class TestLifecycleAllocator extends Allocator<Boolean> {
		int lifecycleCount;
		
		@NotNull
		public Boolean allocate() {
			lifecycleCount++;
			return true;
		}
		
		public void allocateForReuse(Boolean object) {
			lifecycleCount++;
		}
		
		public void deallocateForReuse(Boolean object) {
			lifecycleCount++;
		}
		
		public void deallocate(Boolean object) {
			lifecycleCount++;
		}
	}
}
