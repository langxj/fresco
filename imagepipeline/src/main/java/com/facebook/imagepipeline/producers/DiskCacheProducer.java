/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.producers;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

import com.facebook.common.internal.ImmutableMap;
import com.facebook.common.internal.VisibleForTesting;
import com.facebook.common.references.CloseableReference;
import com.facebook.imagepipeline.cache.BufferedDiskCache;
import com.facebook.imagepipeline.cache.CacheKeyFactory;
import com.facebook.imagepipeline.memory.PooledByteBuffer;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.cache.common.CacheKey;

import bolts.Continuation;
import bolts.Task;

/**
 * Disk cache producer.
 *
 * <p>This producer looks in the disk cache for the requested image. If the image is found, then it
 * is passed to the consumer. If the image is not found, then the request is passed to the next
 * producer in the sequence. Any results that the producer returns are passed to the consumer, and
 * the last result is also put into the disk cache.
 *
 * <p>This implementation delegates disk cache requests to BufferedDiskCache.
 */
public class DiskCacheProducer implements Producer<CloseableReference<PooledByteBuffer>> {
  @VisibleForTesting static final String PRODUCER_NAME = "DiskCacheProducer";
  @VisibleForTesting static final String VALUE_FOUND = "cached_value_found";

  private final BufferedDiskCache mDefaultBufferedDiskCache;
  private final BufferedDiskCache mSmallImageBufferedDiskCache;
  private final CacheKeyFactory mCacheKeyFactory;
  private final Producer<CloseableReference<PooledByteBuffer>> mNextProducer;

  public DiskCacheProducer(
      BufferedDiskCache defaultBufferedDiskCache,
      BufferedDiskCache smallImageBufferedDiskCache,
      CacheKeyFactory cacheKeyFactory,
      Producer<CloseableReference<PooledByteBuffer>> nextProducer) {
    mDefaultBufferedDiskCache = defaultBufferedDiskCache;
    mSmallImageBufferedDiskCache = smallImageBufferedDiskCache;
    mCacheKeyFactory = cacheKeyFactory;
    mNextProducer = nextProducer;
  }

  public void produceResults(
      final Consumer<CloseableReference<PooledByteBuffer>> consumer,
      final ProducerContext producerContext) {
    final ProducerListener listener = producerContext.getListener();
    final String requestId = producerContext.getId();
    listener.onProducerStart(requestId, PRODUCER_NAME);

    ImageRequest imageRequest = producerContext.getImageRequest();
    final CacheKey cacheKey = mCacheKeyFactory.getEncodedCacheKey(imageRequest);
    final BufferedDiskCache cache =
        imageRequest.getImageType() == ImageRequest.ImageType.SMALL
            ? mSmallImageBufferedDiskCache
            : mDefaultBufferedDiskCache;
    Continuation<CloseableReference<PooledByteBuffer>, Void> continuation =
        new Continuation<CloseableReference<PooledByteBuffer>, Void>() {
          @Override
          public Void then(Task<CloseableReference<PooledByteBuffer>> task)
              throws Exception {
            if (task.isCancelled() ||
                (task.isFaulted() && task.getError() instanceof CancellationException)) {
              listener.onProducerFinishWithCancellation(requestId, PRODUCER_NAME, null);
              consumer.onCancellation();
            } else if (task.isFaulted()) {
              listener.onProducerFinishWithFailure(requestId, PRODUCER_NAME, task.getError(), null);
              mNextProducer.produceResults(
                  new DiskCacheConsumer(consumer, cache, cacheKey),
                  producerContext);
            } else {
              CloseableReference<PooledByteBuffer> cachedReference = task.getResult();
              if (cachedReference != null) {
                listener.onProducerFinishWithSuccess(
                    requestId,
                    PRODUCER_NAME,
                    getExtraMap(listener, requestId, true));
                consumer.onNewResult(cachedReference, true);
                cachedReference.close();
              } else {
                listener.onProducerFinishWithSuccess(
                    requestId,
                    PRODUCER_NAME,
                    getExtraMap(listener, requestId, false));
                mNextProducer.produceResults(
                    new DiskCacheConsumer(consumer, cache, cacheKey),
                    producerContext);
              }
            }
            return null;
          }
        };

    AtomicBoolean isCancelled = new AtomicBoolean(false);
    final Task<CloseableReference<PooledByteBuffer>> diskCacheLookupTask =
        cache.get(cacheKey, isCancelled);
    diskCacheLookupTask.continueWith(continuation);
    subscribeTaskForRequestCancellation(isCancelled, producerContext);
  }

  @VisibleForTesting
  static Map<String, String> getExtraMap(
      final ProducerListener listener,
      final String requestId,
      final boolean valueFound) {
    if (!listener.requiresExtraMap(requestId)) {
      return null;
    }
    return ImmutableMap.of(VALUE_FOUND, String.valueOf(valueFound));
  }

  private void subscribeTaskForRequestCancellation(
      final AtomicBoolean isCancelled,
      ProducerContext producerContext) {
    producerContext.addCallbacks(
        new BaseProducerContextCallbacks() {
          @Override
          public void onCancellationRequested() {
            isCancelled.set(true);
          }
        });
  }

  /**
   * Consumer that consumes results from next producer in the sequence.
   *
   * <p>The consumer puts the last result received into disk cache, and passes all results (success
   * or failure) down to the next consumer.
   */
  private class DiskCacheConsumer extends BaseConsumer<CloseableReference<PooledByteBuffer>> {
    private final Consumer<CloseableReference<PooledByteBuffer>> mConsumer;
    private final BufferedDiskCache mCache;
    private final CacheKey mCacheKey;

    private DiskCacheConsumer(
        final Consumer<CloseableReference<PooledByteBuffer>> consumer,
        final BufferedDiskCache cache,
        final CacheKey cacheKey) {
      mConsumer = consumer;
      mCache = cache;
      mCacheKey = cacheKey;
    }

    @Override
    public void onNewResultImpl(CloseableReference<PooledByteBuffer> newResult, boolean isLast) {
      if (newResult != null && isLast) {
        mCache.put(mCacheKey, newResult);
      }
      mConsumer.onNewResult(newResult, isLast);
    }

    @Override
    public void onFailureImpl(Throwable t) {
      mConsumer.onFailure(t);
    }

    @Override
    public void onCancellationImpl() {
      mConsumer.onCancellation();
    }
  }
}
