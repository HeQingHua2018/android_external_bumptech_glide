package com.bumptech.glide.load.engine;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.ResourceEncoder;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.engine.cache.MemoryCache;
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder;
import com.bumptech.glide.provider.DataLoadProvider;
import com.bumptech.glide.request.ResourceCallback;
import com.bumptech.glide.tests.BackgroundUtil;
import com.bumptech.glide.tests.GlideShadowLooper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, emulateSdk = 18, shadows = { GlideShadowLooper.class })
public class EngineTest {
    private static final String ID = "asdf";
    private EngineTestHarness harness;

    @Before
    public void setUp() {
        harness = new EngineTestHarness();
    }

    @Test
    public void testNewRunnerIsCreatedAndPostedWithNoExistingLoad() {
        harness.doLoad();

        verify(harness.job).start(any(EngineRunnable.class));
    }

    @Test
    public void testCallbackIsAddedToNewEngineJobWithNoExistingLoad() {
        harness.doLoad();

        verify(harness.job).addCallback(eq(harness.cb));
    }

    @Test
    public void testLoadStatusIsReturnedForNewLoad() {
        assertNotNull(harness.doLoad());
    }

    @Test
    public void testEngineJobReceivesRemoveCallbackFromLoadStatus() {
        Engine.LoadStatus loadStatus = harness.doLoad();
        loadStatus.cancel();

        verify(harness.job).removeCallback(eq(harness.cb));
    }

    @Test
    public void testNewRunnerIsAddedToRunnersMap() {
        harness.doLoad();

        assertThat(harness.jobs).containsKey(harness.cacheKey);
    }

    @Test
    public void testNewRunnerIsNotCreatedAndPostedWithExistingLoad() {
        harness.doLoad();
        harness.doLoad();

        verify(harness.job, times(1)).start(any(EngineRunnable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCallbackIsAddedToExistingRunnerWithExistingLoad() {
        harness.doLoad();

        ResourceCallback newCallback = mock(ResourceCallback.class);
        harness.cb = newCallback;
        harness.doLoad();

        verify(harness.job).addCallback(eq(newCallback));
    }

    @Test
    public void testLoadStatusIsReturnedForExistingJob() {
        harness.doLoad();
        Engine.LoadStatus loadStatus = harness.doLoad();

        assertNotNull(loadStatus);
    }

    @Test
    public void testResourceIsReturnedFromActiveResourcesIfPresent() {
        harness.activeResources.put(harness.cacheKey, new WeakReference<EngineResource<?>>(harness.resource));

        harness.doLoad();

        verify(harness.cb).onResourceReady(eq(harness.resource));
    }

    @Test
    public void testResourceIsNotReturnedFromActiveResourcesIfRefIsCleared() {
        harness.activeResources.put(harness.cacheKey, new WeakReference<EngineResource<?>>(null));

        harness.doLoad();

        verify(harness.cb, never()).onResourceReady(isNull(Resource.class));
    }

    @Test
    public void testKeyIsRemovedFromActiveResourcesIfRefIsCleared() {
        harness.activeResources.put(harness.cacheKey, new WeakReference<EngineResource<?>>(null));

        harness.doLoad();

        assertThat(harness.activeResources).doesNotContainKey(harness.cacheKey);
    }

    @Test
    public void testResourceIsAcquiredIfReturnedFromActiveResources() {
        harness.activeResources.put(harness.cacheKey, new WeakReference<EngineResource<?>>(harness.resource));

        harness.doLoad();

        verify(harness.resource).acquire();
    }

    @Test
    public void testNewLoadIsNotStartedIfResourceIsActive() {
        harness.activeResources.put(harness.cacheKey, new WeakReference<EngineResource<?>>(harness.resource));

        harness.doLoad();

        verify(harness.job, never()).start(any(EngineRunnable.class));
    }

    @Test
    public void testNullLoadStatusIsReturnedIfResourceIsActive() {
        harness.activeResources.put(harness.cacheKey, new WeakReference<EngineResource<?>>(harness.resource));

        assertNull(harness.doLoad());
    }

    @Test
    public void testActiveResourcesIsNotCheckedIfReturnedFromCache() {
        when(harness.cache.remove(eq(harness.cacheKey))).thenReturn(harness.resource);
        EngineResource other = mock(EngineResource.class);
        harness.activeResources.put(harness.cacheKey, new WeakReference<EngineResource<?>>(other));

        harness.doLoad();

        verify(harness.cb).onResourceReady(eq(harness.resource));
        verify(harness.cb, never()).onResourceReady(eq(other));
    }

    @Test
    public void testCacheIsChecked() {
        when(harness.cache.remove(eq(harness.cacheKey))).thenReturn(harness.resource);

        harness.doLoad();

        verify(harness.cb).onResourceReady(eq(harness.resource));
    }

    @Test
    public void testResourceIsReturnedFromCacheIfPresent() {
        when(harness.cache.remove(eq(harness.cacheKey))).thenReturn(harness.resource);

        harness.doLoad();

        verify(harness.cb).onResourceReady(eq(harness.resource));
    }


    @Test
    public void testHandlesNonEngineResourcesFromCacheIfPresent() {
        final Object expected = new Object();
        Resource fromCache = mock(Resource.class);
        when(fromCache.get()).thenReturn(expected);
        when(harness.cache.remove(eq(harness.cacheKey))).thenReturn(fromCache);

        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Resource resource = (Resource) invocationOnMock.getArguments()[0];
                assertEquals(expected, resource.get());
                return null;
            }
        }).when(harness.cb).onResourceReady(any(Resource.class));

        harness.doLoad();

        verify(harness.cb).onResourceReady(any(Resource.class));
    }

    @Test
    public void testResourceIsAddedToActiveResourceIfReturnedFromCache() {
        when(harness.cache.remove(eq(harness.cacheKey))).thenReturn(harness.resource);

        harness.doLoad();

        assertEquals(harness.resource, harness.activeResources.get(harness.cacheKey)
                .get());
    }

    @Test
    public void testResourceIsAcquiredIfReturnedFromCache() {
        when(harness.cache.remove(eq(harness.cacheKey))).thenReturn(harness.resource);

        harness.doLoad();

        verify(harness.resource).acquire();
    }

    @Test
    public void testNewLoadIsNotStartedIfResourceIsCached() {
        when(harness.cache.remove(eq(harness.cacheKey))).thenReturn(mock(EngineResource.class));

        harness.doLoad();

        verify(harness.job, never()).start(any(EngineRunnable.class));
    }

    @Test
    public void testNullLoadStatusIsReturnedForCachedResource() {
        when(harness.cache.remove(eq(harness.cacheKey))).thenReturn(mock(EngineResource.class));

        Engine.LoadStatus loadStatus = harness.doLoad();
        assertNull(loadStatus);
    }

    @Test
    public void testRunnerIsRemovedFromRunnersOnEngineNotifiedJobComplete() {
        harness.doLoad();

        harness.engine.onEngineJobComplete(harness.cacheKey, harness.resource);

        assertThat(harness.jobs).doesNotContainKey(harness.cacheKey);
    }

    @Test
    public void testEngineIsSetAsResourceListenerOnJobComplete() {
        harness.doLoad();

        harness.engine.onEngineJobComplete(harness.cacheKey, harness.resource);

        verify(harness.resource).setResourceListener(eq(harness.cacheKey), eq(harness.engine));
    }

    @Test
    public void testEngineIsNotSetAsResourceListenerIfResourceIsNullOnJobComplete() {
        harness.doLoad();

        harness.engine.onEngineJobComplete(harness.cacheKey, null);
    }

    @Test
    public void testResourceIsAddedToActiveResourcesOnEngineComplete() {
        harness.engine.onEngineJobComplete(harness.cacheKey, harness.resource);

        WeakReference<EngineResource<?>> resourceRef = harness.activeResources.get(harness.cacheKey);
        assertEquals(harness.resource, resourceRef.get());
    }

    @Test
    public void testDoesNotPutNullResourceInActiveResourcesOnEngineComplete() {
        harness.engine.onEngineJobComplete(harness.cacheKey, null);
        assertThat(harness.activeResources).doesNotContainKey(harness.cacheKey);
    }

    @Test
    public void testRunnerIsRemovedFromRunnersOnEngineNotifiedJobCancel() {
        harness.doLoad();

        harness.engine.onEngineJobCancelled(harness.job, harness.cacheKey);

        assertThat(harness.jobs).doesNotContainKey(harness.cacheKey);
    }


    @Test
    public void testJobIsNotRemovedFromJobsIfOldJobIsCancelled() {
        harness.doLoad();

        harness.engine.onEngineJobCancelled(mock(EngineJob.class), harness.cacheKey);

        assertEquals(harness.job, harness.jobs.get(harness.cacheKey));
    }

    @Test
    public void testResourceIsAddedToCacheOnReleased() {
        final Object expected = new Object();
        when(harness.resource.isCacheable()).thenReturn(true);
        when(harness.resource.get()).thenReturn(expected);
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                Resource<?> resource = (Resource<?>) invocationOnMock.getArguments()[1];
                assertEquals(expected, resource.get());
                return null;
            }
        }).when(harness.cache).put(eq(harness.cacheKey), any(Resource.class));

        harness.engine.onResourceReleased(harness.cacheKey, harness.resource);


        verify(harness.cache).put(eq(harness.cacheKey), any(Resource.class));
    }

    @Test
    public void testResourceIsNotAddedToCacheOnReleasedIfNotCacheable() {
        when(harness.resource.isCacheable()).thenReturn(false);
        harness.engine.onResourceReleased(harness.cacheKey, harness.resource);

        verify(harness.cache, never()).put(eq(harness.cacheKey), eq(harness.resource));
    }

    @Test
    public void testResourceIsRecycledIfNotCacheableWhenReleased() {
        when(harness.resource.isCacheable()).thenReturn(false);
        harness.engine.onResourceReleased(harness.cacheKey, harness.resource);
        verify(harness.resourceRecycler).recycle(eq(harness.resource));
    }

    @Test
    public void testResourceIsRemovedFromActiveResourcesWhenReleased() {
        harness.activeResources.put(harness.cacheKey, new WeakReference<EngineResource<?>>(harness.resource));

        harness.engine.onResourceReleased(harness.cacheKey, harness.resource);

        assertThat(harness.activeResources).doesNotContainKey(harness.cacheKey);
    }

    @Test
    public void testEngineAddedAsListenerToMemoryCache() {
        verify(harness.cache).setResourceRemovedListener(eq(harness.engine));
    }

    @Test
    public void testResourceIsRecycledWhenRemovedFromCache() {
        harness.engine.onResourceRemoved(harness.resource);
        verify(harness.resourceRecycler).recycle(eq(harness.resource));
    }

    @Test
    public void testJobIsPutInJobWithCacheKeyWithRelevantIds() {
        harness.doLoad();

        assertThat(harness.jobs).containsEntry(harness.cacheKey, harness.job);
    }

    @Test
    public void testKeyFactoryIsGivenNecessaryArguments() {
        harness.doLoad();

        verify(harness.keyFactory).buildKey(eq(ID), eq(harness.signature), eq(harness.width), eq(harness.height),
                eq(harness.cacheDecoder), eq(harness.decoder), eq(harness.transformation), eq(harness.encoder),
                eq(harness.transcoder), eq(harness.sourceEncoder));
    }

    @Test
    public void testFactoryIsGivenNecessaryArguments() {
        harness.doLoad();

        verify(harness.engineJobFactory).build(eq(harness.cacheKey), eq(harness.isMemoryCacheable));
    }

    @Test
    public void testReleaseReleasesEngineResource() {
        EngineResource<Object> engineResource = mock(EngineResource.class);
        harness.engine.release(engineResource);
        verify(engineResource).release();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testThrowsIfAskedToReleaseNonEngineResource() {
        harness.engine.release(mock(Resource.class));
    }

    @Test(expected = RuntimeException.class)
    public void testThrowsIfLoadCalledOnBackgroundThread() throws InterruptedException {
        BackgroundUtil.testInBackground(new BackgroundUtil.BackgroundTester() {
            @Override
            public void runTest() throws Exception {
                harness.doLoad();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static class EngineTestHarness {
        EngineKey cacheKey = mock(EngineKey.class);
        EngineKeyFactory keyFactory = mock(EngineKeyFactory.class);
        ResourceDecoder<File, Object> cacheDecoder = mock(ResourceDecoder.class);
        DataFetcher<Object> fetcher = mock(DataFetcher.class);
        ResourceDecoder<Object, Object> decoder = mock(ResourceDecoder.class);
        ResourceEncoder<Object> encoder = mock(ResourceEncoder.class);
        ResourceTranscoder<Object, Object> transcoder = mock(ResourceTranscoder.class);
        Priority priority = Priority.NORMAL;
        ResourceCallback cb = mock(ResourceCallback.class);
        EngineResource resource = mock(EngineResource.class);
        Map<Key, EngineJob> jobs = new HashMap<Key, EngineJob>();
        Transformation transformation = mock(Transformation.class);
        Map<Key, WeakReference<EngineResource<?>>> activeResources =
                new HashMap<Key, WeakReference<EngineResource<?>>>();
        Encoder<Object> sourceEncoder = mock(Encoder.class);
        DiskCacheStrategy diskCacheStrategy = DiskCacheStrategy.RESULT;
        Key signature = mock(Key.class);

        int width = 100;
        int height = 100;

        MemoryCache cache = mock(MemoryCache.class);
        EngineJob job;
        Engine engine;
        boolean isMemoryCacheable;
        Engine.EngineJobFactory engineJobFactory = mock(Engine.EngineJobFactory.class);
        DataLoadProvider<Object, Object> loadProvider = mock(DataLoadProvider.class);
        ResourceRecycler resourceRecycler = mock(ResourceRecycler.class);

        public EngineTestHarness() {
            when(loadProvider.getCacheDecoder()).thenReturn(cacheDecoder);
            when(loadProvider.getSourceEncoder()).thenReturn(sourceEncoder);
            when(loadProvider.getEncoder()).thenReturn(encoder);
            when(loadProvider.getSourceDecoder()).thenReturn(decoder);

            when(keyFactory.buildKey(anyString(), any(Key.class), anyInt(), anyInt(), any(ResourceDecoder.class),
                    any(ResourceDecoder.class), any(Transformation.class), any(ResourceEncoder.class),
                    any(ResourceTranscoder.class), any(Encoder.class))).thenReturn(cacheKey);
            when(fetcher.getId()).thenReturn(ID);

            job = mock(EngineJob.class);

            engine = new Engine(cache, mock(DiskCache.class), mock(ExecutorService.class),
                    mock(ExecutorService.class), jobs, keyFactory, activeResources, engineJobFactory, resourceRecycler);

            when(engineJobFactory.build(eq(cacheKey), eq(isMemoryCacheable))).thenReturn(job);
        }

        public Engine.LoadStatus doLoad() {
            return engine.load(signature, width, height, fetcher, loadProvider, transformation, transcoder, priority,
                    isMemoryCacheable, diskCacheStrategy, cb);
        }
    }
}
