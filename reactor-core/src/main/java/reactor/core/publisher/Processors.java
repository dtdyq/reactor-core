/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.FluxSink.OverflowStrategy;
import reactor.core.scheduler.Scheduler;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;
import reactor.util.concurrent.WaitStrategy;
import reactor.util.context.Context;

/**
 * Utility class to create various flavors of {@link  ProcessorSink Flux Processors}.
 *
 * @author Simon Baslé
 */
public final class Processors {

	/**
	 * Create a "direct" {@link ProcessorSink}: it can dispatch signals to zero to many
	 * {@link Subscriber Subscribers}, but has the limitation of not
	 * handling backpressure.
	 * <p>
	 * As a consequence, a direct Processor signals an {@link IllegalStateException} to its
	 * subscribers if you push N elements through it but at least one of its subscribers has
	 * requested less than N.
	 * <p>
	 * Once the Processor has terminated (usually through its {@link FluxSink#error(Throwable)}
	 * or {@link FluxSink#complete()} methods being called), it lets more subscribers
	 * subscribe but replays the termination signal to them immediately.
	 *
	 * @param <T> the type of the data flowing through the processor
	 * @return a new direct {@link ProcessorSink}
	 */
	@SuppressWarnings("deprecation")
	public static final <T> FluxProcessorSink<T> direct() {
		return new FluxProcessorSinkAdapter<>(new DirectProcessor<>(), null);
	}

	/**
	 * Create an unbounded "unicast" {@link ProcessorSink}, which can deal with
	 * backpressure using an internal buffer, but <strong>can have at most one
	 * {@link Subscriber}</strong>.
	 * <p>
	 * The returned unicast Processor is unbounded: if you push any amount of data through
	 * it while its {@link Subscriber} has not yet requested data, it will buffer
	 * all of the data. Use the builder variant to provide a {@link Queue} through the
	 * {@link #unicast(Queue)} method, which returns a builder that allows further
	 * configuration.
	 *
	 * @param <T>
	 * @return a new unicast {@link ProcessorSink}
	 */
	public static final <T> FluxProcessorSink<T> unicast() {
		return new FluxProcessorSinkAdapter<>(UnicastProcessor.create(), null);
	}

	/**
	 * Create a builder for a "unicast" {@link ProcessorSink}, which can deal with
	 * backpressure using an internal buffer, but <strong>can have at most one
	 * {@link Subscriber}</strong>.
	 * <p>
	 * This unicast Processor can be fine tuned through its builder, but it requires at
	 * least a {@link Queue}. If said queue is unbounded and if you push any amount of
	 * data through the processor while its {@link Subscriber} has not yet requested data,
	 * it will buffer all of the data. You can avoid that by providing a bounded {@link Queue}
	 * instead.
	 *
	 * @param queue the {@link Queue} to back the processor, making it bounded or unbounded
	 * @param <T>
	 * @return a builder to create a new unicast {@link ProcessorSink}
	 */
	public static final <T> UnicastProcessorBuilder<T> unicast(Queue<T> queue) {
		return new UnicastProcessorBuilder<>(queue);
	}

	/**
	 * Create a new "emitter" {@link ProcessorSink}, which is capable
	 * of emitting to several {@link Subscriber Subscribers} while honoring backpressure
	 * for each of its subscribers. It can also subscribe to a {@link Publisher} and relay
	 * its signals synchronously.
	 *
	 * <p>
	 * Initially, when it has no {@link Subscriber}, this emitter Processor can still accept
	 * a few data pushes up to {@link Queues#SMALL_BUFFER_SIZE}.
	 * After that point, if no {@link Subscriber} has come in and consumed the data,
	 * calls to {@link FluxProcessorSink#next(Object) onNext} block until the processor
	 * is drained (which can only happen concurrently by then).
	 *
	 * <p>
	 * Thus, the first {@link Subscriber} to subscribe receives up to {@code bufferSize}
	 * elements upon subscribing. However, after that, the processor stops replaying signals
	 * to additional subscribers. These subsequent subscribers instead only receive the
	 * signals pushed through the processor after they have subscribed. The internal buffer
	 * is still used for backpressure purposes.
	 *
	 * <p>
	 * The returned emitter Processor is {@code auto-cancelling}, which means that when
	 * all the Processor's subscribers are cancelled (ie. they have all un-subscribed),
	 * it will clear its internal buffer and stop accepting new subscribers. This can be
	 * tuned by creating a processor through the builder method, {@link #emitter(int)}.
	 *
	 * @return a new auto-cancelling emitter {@link ProcessorSink}
	 */
	public static final <T> FluxProcessorSink<T> emitter() {
		return new EmitterProcessorBuilder(-1).build();
	}

	/**
	 * Create a builder for an "emitter" {@link ProcessorSink}, which is capable
	 * of emitting to several {@link Subscriber Subscribers} while honoring backpressure
	 * for each of its subscribers. It can also subscribe to a {@link Publisher} and relay
	 * its signals synchronously.
	 *
	 * <p>
	 * Initially, when it has no {@link Subscriber}, an emitter Processor can still accept
	 * a few data pushes up to a configurable {@code bufferSize}.
	 * After that point, if no {@link Subscriber} has come in and consumed the data,
	 * calls to {@link FluxProcessorSink#next(Object)} block until the processor
	 * is drained (which can only happen concurrently by then).
	 *
	 * <p>
	 * Thus, the first {@link Subscriber} to subscribe receives up to {@code bufferSize}
	 * elements upon subscribing. However, after that, the processor stops replaying signals
	 * to additional subscribers. These subsequent subscribers instead only receive the
	 * signals pushed through the processor after they have subscribed. The internal buffer
	 * is still used for backpressure purposes.
	 *
	 * <p>
	 * By default, if all of the emitter Processor's subscribers are cancelled (which
	 * basically means they have all un-subscribed), it will clear its internal buffer and
	 * stop accepting new subscribers. This can be deactivated by using the
	 * {@link EmitterProcessorBuilder#noAutoCancel()} method in the builder.
	 *
	 * @param bufferSize the size of the initial replay buffer (must be positive)
	 * @return a builder to create a new emitter {@link ProcessorSink}
	 */
	public static final EmitterProcessorBuilder emitter(int bufferSize) {
		if (bufferSize < 0) {
			throw new IllegalArgumentException("bufferSize must be positive");
		}
		return new EmitterProcessorBuilder(bufferSize);
	}

	/**
	 * Create a new unbounded "replay" {@link ProcessorSink}, which caches
	 * elements that are either pushed directly through it or elements from an upstream
	 * {@link Publisher}, and replays them to late {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Replay Processors can be created in multiple configurations (this method creates
	 * an unbounded variant):
	 * <ul>
	 *     <li>
	 *         Caching an unbounded history (call to this method, or the {@link #replay(int) size variant}
	 *         of the replay builder with a call to its {@link ReplayProcessorBuilder#unbounded()}
	 *         method).
	 *     </li>
	 *     <li>
	 *         Caching a bounded history ({@link #replay(int)}).
	 *     </li>
	 *     <li>
	 *         Caching time-based replay windows (by only specifying a TTL in the time-oriented
	 *         builder {@link #replay(Duration)}).
	 *     </li>
	 *     <li>
	 *         Caching combination of history size and time window (by using the time-oriented
	 *         builder {@link #replay(Duration)} and configuring a size on it via its
	 *         {@link ReplayTimeProcessorBuilder#historySize(int)} method).
	 *     </li>
	 * </ul>
	 *
	 * @return a builder to create a new replay {@link ProcessorSink}
	 */
	public static final <T> FluxProcessorSink<T> replay() {
		return new FluxProcessorSinkAdapter<>(ReplayProcessor.create(), null);
	}

	/**
	 * Create a builder for a "replay" {@link ProcessorSink}, which caches
	 * elements that are either pushed directly through it or elements from an upstream
	 * {@link Publisher}, and replays them to late {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Replay Processors can be created in multiple configurations (this builder allows to
	 * create all time-oriented variants):
	 * <ul>
	 *     <li>
	 *         Caching an unbounded history (call to {@link #replay()}, or the
	 *         {@link #replay(int) size variant} of the replay builder with a call to its
	 *         {@link ReplayProcessorBuilder#unbounded()} method).
	 *     </li>
	 *     <li>
	 *         Caching a bounded history ({@link #replay(int)}).
	 *     </li>
	 *     <li>
	 *         Caching time-based replay windows (by only specifying a TTL in this builder).
	 *     </li>
	 *     <li>
	 *         Caching combination of history size and time window (by using this builder
	 *         to configure a size via the {@link ReplayTimeProcessorBuilder#historySize(int)}
	 *         method).
	 *     </li>
	 * </ul>
	 *
	 * @return a builder to create a new replay {@link ProcessorSink}
	 */
	public static final ReplayTimeProcessorBuilder replay(Duration maxAge) {
		return new ReplayTimeProcessorBuilder(maxAge);
	}

	/**
	 * Create a builder for a "replay" {@link ProcessorSink}, which caches
	 * elements that are either pushed directly through it or elements from an upstream
	 * {@link Publisher}, and replays them to late {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Replay Processors can be created in multiple configurations (this builder exclusively
	 * creates purely size-oriented variants):
	 * <ul>
	 *     <li>
	 *         Caching an unbounded history (call to {@link #replay()} or this builder
	 *         with a call to its {@link ReplayProcessorBuilder#unbounded()} method).
	 *     </li>
	 *     <li>
	 *         Caching a bounded history (this builder).
	 *     </li>
	 *     <li>
	 *         Caching time-based replay windows (by only specifying a TTL in the time-oriented
	 *         builder {@link #replay(Duration)}).
	 *     </li>
	 *     <li>
	 *         Caching combination of history size and time window (by using the time-oriented
	 *         builder {@link #replay(Duration)} and configuring a size on it via its
	 *         {@link ReplayTimeProcessorBuilder#historySize(int)} method).
	 *     </li>
	 * </ul>
	 *
	 * @return a builder to create a new replay {@link ProcessorSink}
	 */
	public static final ReplayProcessorBuilder replay(int historySize) {
		return new ReplayProcessorBuilder(historySize);
	}

	/**
	 * Create a new "replay" {@link ProcessorSink} (see {@link #replay()}) that
	 * caches the last element it has pushed, replaying it to late {@link Subscriber subscribers}.
	 *
	 * @param <T>
	 * @return a new replay {@link ProcessorSink} that caches its last pushed element
	 */
	public static final <T> FluxProcessorSink<T> cacheLast() {
		return new FluxProcessorSinkAdapter<>(ReplayProcessor.cacheLast(), null);
	}

	/**
	 * Create a new "replay" {@link ProcessorSink} (see {@link #replay()}) that
	 * caches the last element it has pushed, replaying it to late {@link Subscriber subscribers}.
	 * If a {@link Subscriber} comes in <strong>before</strong> any value has been pushed,
	 * then the {@code defaultValue} is emitted instead.
	 *
	 * @param <T>
	 * @return a new replay {@link ProcessorSink} that caches its last pushed element
	 */
	public static final <T> FluxProcessorSink<T> cacheLastOrDefault(T defaultValue) {
		return new FluxProcessorSinkAdapter<>(ReplayProcessor.cacheLastOrDefault(defaultValue), null);
	}

	/**
	 * Create a builder for a "fan out" {@link ProcessorSink}, which is an
	 * <strong>asynchronous</strong> processor optionally capable of relaying elements from multiple
	 * upstream {@link Publisher Publishers} when created in the shared configuration (see the {@link
	 * FanOutProcessorBuilder#share(boolean)} option of the builder).
	 *
	 * <p>
	 * Note that the share option is mandatory if you intend to concurrently call the
	 * {@link FluxProcessorSink#next(Object)}, {@link FluxProcessorSink#complete()} or
	 * {@link FluxProcessorSink#error(Throwable)} methods directly or from a concurrent upstream
	 * {@link Publisher}.
	 *
	 * <p>
	 * Otherwise, such concurrent calls are illegal, as the processor is then fully compliant with
	 * the Reactive Streams specification.
	 *
	 * <p>
	 * A fan out processor is capable of fanning out to multiple {@link Subscriber Subscribers},
	 * with the added overhead of establishing resources to keep track of each {@link Subscriber}
	 * until an {@link ProcessorSink#error(Throwable)} or {@link FluxProcessorSink#complete()}
	 * signal is pushed through the processor or until the associated {@link Subscriber} is cancelled.
	 *
	 * <p>
	 * This variant uses a {@link Thread}-per-{@link Subscriber} model.
	 *
	 * <p>
	 * The maximum number of downstream subscribers for this processor is driven by the {@link
	 * FanOutProcessorBuilder#executor(ExecutorService) executor} builder option. Provide a bounded
	 * {@link ExecutorService} to limit it to a specific number.
	 *
	 * <p>
	 * There is also an {@link FanOutProcessorBuilder#autoCancel(boolean) autoCancel} builder
	 * option: Set it to {@code false} to avoid cancelling the source {@link Publisher Publisher(s)}
	 * when all subscribers are cancelled.
	 *
	 * @return a builder to create a new fan out {@link ProcessorSink}
	 */
	public static final FanOutProcessorBuilder fanOut() {
		return new FanOutProcessorBuilder();
	}

	/**
	 * Create a builder for a "fan out" {@link ProcessorSink} with relaxed
	 * Reactive Streams compliance. This is an <strong>asynchronous</strong> processor
	 * optionally capable of relaying elements from multiple upstream {@link Publisher Publishers}
	 * when created in the shared configuration (see the {@link FanOutProcessorBuilder#share(boolean)}
	 * option of the builder).
	 *
	 * <p>
	 * Note that the share option is mandatory if you intend to concurrently call the Processor's
	 * {@link FluxProcessorSink#next(Object)}, {@link FluxProcessorSink#complete()} ()}, or
	 * {@link FluxProcessorSink#error(Throwable)} methods directly or from a concurrent upstream
	 * {@link Publisher}. Otherwise, such concurrent calls are illegal.
	 *
	 * <p>
	 * A fan out processor is capable of fanning out to multiple {@link Subscriber Subscribers},
	 * with the added overhead of establishing resources to keep track of each {@link Subscriber}
	 * until an {@link FluxProcessorSink#error(Throwable)} or {@link
	 * FluxProcessorSink#complete()} signal is pushed through the processor or until the
	 * associated {@link Subscriber} is cancelled.
	 *
	 * <p>
	 * This variant uses a RingBuffer and doesn't have the overhead of keeping track of
	 * each {@link Subscriber} in its own Thread, so it scales better. As a trade-off,
	 * its compliance with the Reactive Streams specification is slightly
	 * relaxed, and its distribution pattern is to add up requests from all {@link Subscriber Subscribers}
	 * together and to relay signals to only one {@link Subscriber}, picked in a kind of
	 * round-robin fashion.
	 *
	 * <p>
	 * The maximum number of downstream subscribers for this processor is driven by the {@link
	 * FanOutProcessorBuilder#executor(ExecutorService) executor} builder option. Provide a bounded
	 * {@link ExecutorService} to limit it to a specific number.
	 *
	 * <p>
	 * There is also an {@link FanOutProcessorBuilder#autoCancel(boolean) autoCancel} builder
	 * option: If set to {@code true} (the default), it results in the source {@link Publisher
	 * Publisher(s)} being cancelled when all subscribers are cancelled.
	 *
	 * @return a builder to create a new round-robin fan out {@link ProcessorSink}
	 */
	@Deprecated
	public static final FanOutProcessorBuilder relaxedFanOut() {
		return new FanOutProcessorBuilder(true);
	}

	/**
	 * Create a "first" {@link ProcessorSink}, which will wait for a source
	 * {@link Subscriber#onSubscribe(Subscription)}. It will then propagate only the first
	 * incoming {@link Subscriber#onNext(Object) onNext} signal, and cancel the source
	 * subscription (unless it is a {@link Mono}). The processor will replay that signal
	 * to new {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Manual usage by pushing events through the {@link MonoSink} is naturally limited to at most
	 * one call to {@link MonoSink#success(Object)}.
	 *
	 * @param <T> the type of the processor
	 * @return a new "first" {@link ProcessorSink} that is detached
	 */
	public static final <T> ProcessorSink<T> first() {
		return new MonoFirstProcessorSinkAdapter<>(new MonoProcessor<>(null));
	}

	/**
	 * Create a "first" {@link ProcessorSink}, which will wait for a source
	 * {@link Subscriber#onSubscribe(Subscription)}. It will then propagate only the first
	 * incoming {@link Subscriber#onNext(Object) onNext} signal, and cancel the source
	 * subscription (unless it is a {@link Mono}). The processor will replay that signal
	 * to new {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Manual usage by pushing events through the {@link MonoSink} is naturally limited to at most
	 * one call to {@link MonoSink#success(Object)}.
	 *
	 * @param waitStrategy the {@link WaitStrategy} to use in blocking methods of the processor
	 * @param <T> the type of the processor
	 * @return a new "first" {@link ProcessorSink} that is detached
	 */
	public static final <T> ProcessorSink<T> first(WaitStrategy waitStrategy) {
		return new MonoFirstProcessorSinkAdapter<>(new MonoProcessor<>(null, waitStrategy));
	}

	/**
	 * Create a builder for a "first" {@link ProcessorSink} that is attached to a
	 * {@link Publisher} source, of which it will propagate only the first incoming
	 * {@link Subscriber#onNext(Object) onNext} signal. Once this is done, the processor
	 * cancels the source subscription (unless it is a {@link Mono}). It will then
	 * replay that signal to new {@link Subscriber Subscribers}.
	 *
	 * <p>
	 * Manual usage by pushing events through the {@link MonoSink} is naturally limited to at most
	 * one call to {@link MonoSink#success(Object)}.
	 *
	 * @param source the source to attach to
	 * @param <T> the type of the source, which drives the type of the processor
	 * @return a builder to create a new "first" {@link ProcessorSink} attached to a source
	 */
	public static final <T> MonoFirstProcessorBuilder<T> first(Publisher<? extends T> source) {
		return new MonoFirstProcessorBuilder<>(source);
	}

	//=== BUILDERS to replace factory method only processors ===

	/**
	 * A builder for the {@link #unicast()} flavor of {@link ProcessorSink}.
	 *
	 * @param <T>
	 */
	public static final class UnicastProcessorBuilder<T> {

		private final Queue<T> queue;
		private Disposable endcallback;
		private OverflowStrategy overflowStrategy;
		private Consumer<? super T> onOverflow;

		/**
		 * A unicast Processor created through {@link Processors#unicast()} is unbounded.
		 * This can be changed by using this builder, which also allows further configuration.
		 * <p>
		 * Provide a custom {@link Queue} implementation for the internal buffering.
		 * If that queue is bounded, the processor could reject the push of a value when the
		 * buffer is full and not enough requests from downstream have been received.
		 * <p>
		 * In that bounded case, one can also set a callback to be invoked on each rejected
		 * element, allowing for cleanup of these rejected elements (see {@link #onOverflow(Consumer)}).
		 *
		 * @param q the {@link Queue} to be used for internal buffering
		 */
		UnicastProcessorBuilder(Queue<T> q) {
			this.queue = q;
		}

		/**
		 * Set a callback that will be executed by the Processor on any terminating signal.
		 *
		 * @param e the callback
		 * @return the builder
		 */
		public UnicastProcessorBuilder<T> endCallback(Disposable e) {
			this.endcallback = e;
			return this;
		}

		/**
		 * Set an {@link OverflowStrategy} to use when creating the {@link FluxSink}
		 * backing this {@link FluxProcessorSink}.
		 *
		 * @param overflowStrategy the strategy to use on sink methods
		 * @return the builder
		 */
		public UnicastProcessorBuilder<T> withOverflowStrategy(OverflowStrategy overflowStrategy) {
			this.overflowStrategy = overflowStrategy;
			return this;
		}

		/**
		 * When a bounded {@link #UnicastProcessorBuilder(Queue) queue} has been provided,
		 * set up a callback to be executed on every element rejected by the {@link Queue}
		 * once it is already full.
		 *
		 * @param c the cleanup consumer for overflowing elements in a bounded queue
		 * @return the builder
		 */
		public UnicastProcessorBuilder<T> onOverflow(Consumer<? super T> c) {
			this.onOverflow = c;
			return this;
		}

		/**
		 * Build the unicast {@link FluxProcessorSink} according to the builder's
		 * configuration.
		 *
		 * @return a new unicast {@link FluxProcessorSink Processor}
		 */
		public FluxProcessorSink<T> build() {
			UnicastProcessor<T> processor;
			if (endcallback != null && onOverflow != null) {
				processor = new UnicastProcessor<>(queue, onOverflow, endcallback);
			}
			else if (endcallback != null) {
				processor = new UnicastProcessor<>(queue, endcallback);
			}
			else if (onOverflow == null) {
				processor = new UnicastProcessor<>(queue);
			}
			else {
				processor = new UnicastProcessor<>(queue, onOverflow);
			}

			return new FluxProcessorSinkAdapter<>(processor, overflowStrategy);
		}
	}

	/**
	 * A builder for the {@link #emitter()} flavor of {@link ProcessorSink}.
	 */
	public static final class EmitterProcessorBuilder {

		private final int bufferSize;
		private boolean autoCancel = true;
		private OverflowStrategy overflowStrategy;

		/**
		 * Initialize the builder with the replay capacity of the emitter Processor,
		 * which defines how many elements it will retain while it has no current
		 * {@link Subscriber}. Once that size is reached, if there still is no
		 * {@link Subscriber} the emitter processor will block its calls to
		 * {@link Processor#onNext(Object)} until it is drained (which can only happen
		 * concurrently by then).
		 * <p>
		 * The first {@link Subscriber} to subscribe receives all of these elements, and
		 * further subscribers only see new incoming data after that.
		 *
		 * @param bufferSize the size of the initial no-subscriber bounded buffer
		 */
		EmitterProcessorBuilder(int bufferSize) {
			this.bufferSize = bufferSize;
		}

		/**
		 * By default, if all of its {@link Subscriber Subscribers} are cancelled (which
		 * basically means they have all un-subscribed), the emitter Processor will clear
		 * its internal buffer and stop accepting new subscribers. This method changes
		 * that so that the emitter processor keeps on running.
		 *
		 * @return the builder
		 */
		public EmitterProcessorBuilder noAutoCancel() {
			this.autoCancel = false;
			return this;
		}

		/**
		 * Set an {@link OverflowStrategy} to use when creating the {@link FluxSink}
		 * backing this {@link FluxProcessorSink}.
		 *
		 * @param overflowStrategy the strategy to use on sink methods
		 * @return the builder
		 */
		public EmitterProcessorBuilder withOverflowStrategy(OverflowStrategy overflowStrategy) {
			this.overflowStrategy = overflowStrategy;
			return this;
		}

		/**
		 * Build the emitter {@link ProcessorSink} according to the builder's
		 * configuration.
		 *
		 * @return a new emitter {@link ProcessorSink}
		 */
		public <T> FluxProcessorSink<T> build() {
			EmitterProcessor<T> processor;
			if (bufferSize >= 0 && !autoCancel) {
				processor = new EmitterProcessor<>(false, bufferSize);
			}
			else if (bufferSize >= 0) {
				processor = new EmitterProcessor<>(true, bufferSize);
			}
			else if (!autoCancel) {
				processor = new EmitterProcessor<>(false, Queues.SMALL_BUFFER_SIZE);
			}
			else {
				processor = new EmitterProcessor<>(true, Queues.SMALL_BUFFER_SIZE);
			}

			return new FluxProcessorSinkAdapter<>(processor, overflowStrategy);
		}
	}

	/**
	 * A builder for the size-configured {@link #replay()} flavor of {@link ProcessorSink}.
	 */
	public static final class ReplayProcessorBuilder {

		private final int       size;
		private boolean         unbounded;
		private OverflowStrategy overflowStrategy;

		/**
		 * Set the history capacity to a specific bounded size.
		 *
		 * @param size the history buffer capacity
		 * @return the builder, with a bounded capacity
		 */
		public ReplayProcessorBuilder(int size) {
			this.size = size;
			this.unbounded = false;
		}

		/**
		 * Despite the history capacity being set to a specific initial size, mark the
		 * processor as unbounded: the size will be considered as a hint instead of an
		 * hard limit.
		 *
		 * @return the builder, with an unbounded capacity
		 */
		public ReplayProcessorBuilder unbounded() {
			this.unbounded = true;
			return this;
		}

		/**
		 * Set an {@link OverflowStrategy} to use when creating the {@link FluxSink}
		 * backing this {@link FluxProcessorSink}.
		 *
		 * @param overflowStrategy the strategy to use on sink methods
		 * @return the builder
		 */
		public ReplayProcessorBuilder withOverflowStrategy(OverflowStrategy overflowStrategy) {
			this.overflowStrategy = overflowStrategy;
			return this;
		}

		/**
		 * Build the replay {@link ProcessorSink} according to the builder's configuration.
		 *
		 * @return a new replay {@link ProcessorSink}
		 */
		public <T> FluxProcessorSink<T> build() {
			ReplayProcessor<T> processor;
			if (size < 0) {
				processor = ReplayProcessor.create();
			}
			else if (unbounded) {
				processor = ReplayProcessor.create(size, true);
			}
			else {
				processor = ReplayProcessor.create(size);
			}

			return new FluxProcessorSinkAdapter<>(processor, overflowStrategy);
		}
	}

	/**
	 * A builder for the time-oriented {@link #replay()} flavors of {@link ProcessorSink}.
	 * This can also be used to build a time + size oriented replay processor.
	 */
	public static final class ReplayTimeProcessorBuilder {

		private final Duration  maxAge;
		private int       size = -1;
		private Scheduler scheduler = null;
		private OverflowStrategy overflowStrategy;

		public ReplayTimeProcessorBuilder(Duration maxAge) {
			this.maxAge = maxAge;
		}

		/**
		 * Set the history capacity to a specific bounded size.
		 *
		 * @param size the history buffer capacity
		 * @return the builder, with a bounded capacity
		 */
		public ReplayTimeProcessorBuilder historySize(int size) {
			this.size = size;
			return this;
		}

		/**
		 * Set the {@link Scheduler} to use for measuring time.
		 *
		 * @param ttlScheduler the {@link Scheduler} to be used to enforce the TTL
		 * @return the builder
		 */
		public ReplayTimeProcessorBuilder scheduler(Scheduler ttlScheduler) {
			this.scheduler = ttlScheduler;
			return this;
		}

		/**
		 * Set an {@link OverflowStrategy} to use when creating the {@link FluxSink}
		 * backing this {@link FluxProcessorSink}.
		 *
		 * @param overflowStrategy the strategy to use on sink methods
		 * @return the builder
		 */
		public ReplayTimeProcessorBuilder withOverflowStrategy(OverflowStrategy overflowStrategy) {
			this.overflowStrategy = overflowStrategy;
			return this;
		}

		/**
		 * Build the replay {@link ProcessorSink} according to the builder's configuration.
		 *
		 * @return a new replay {@link ProcessorSink}
		 */
		public <T> ProcessorSink<T> build() {
			//replay size and timeout
			ReplayProcessor<T> processor;
			if (size != -1) {
				if (scheduler != null) {
					processor = ReplayProcessor.createSizeAndTimeout(size, maxAge, scheduler);
				}
				else {
					processor = ReplayProcessor.createSizeAndTimeout(size, maxAge);
				}
			}
			else if (scheduler != null) {
				processor = ReplayProcessor.createTimeout(maxAge, scheduler);
			}
			else {
				processor = ReplayProcessor.createTimeout(maxAge);
			}

			return new FluxProcessorSinkAdapter<>(processor, overflowStrategy);
		}
	}

	/**
	 * A builder for the {@link #fanOut()} flavor of {@link ProcessorSink}.
	 */
	public static final class FanOutProcessorBuilder {

		boolean useWorkQueueProcessor;

		@Nullable
		String name;
		@Nullable
		ExecutorService executor;
		@Nullable
		ExecutorService requestTaskExecutor;
		int bufferSize;
		@Nullable
		WaitStrategy waitStrategy;
		boolean share;
		boolean autoCancel;
		@Nullable
		OverflowStrategy overflowStrategy;

		FanOutProcessorBuilder() {
			this(false);
		}

		FanOutProcessorBuilder(boolean useWorkQueueProcessor) {
			this.bufferSize = Queues.SMALL_BUFFER_SIZE;
			this.autoCancel = true;
			this.share = false;
			this.useWorkQueueProcessor = useWorkQueueProcessor;
		}

		/**
		 * Configures name for this builder, if {@link #executor(ExecutorService)} is not configured.
		 * Default value is {@code "fanOut"}.
		 * Name is reset to default if the provided {@code name} is null.
		 *
		 * @param name Use a new cached ExecutorService and assign this name to the created threads.
		 * @return builder with provided name
		 */
		public FanOutProcessorBuilder name(@Nullable String name) {
			if (executor != null)
				throw new IllegalArgumentException("Executor service is configured, name cannot be set.");
			this.name = name;
			return this;
		}

		/**
		 * Configures buffer size for this builder. Default value is {@link Queues#SMALL_BUFFER_SIZE}.
		 *
		 * @param bufferSize the internal buffer size to hold signals, must be a power of 2.
		 * @return builder with provided buffer size
		 */
		public FanOutProcessorBuilder bufferSize(int bufferSize) {
			if (!Queues.isPowerOfTwo(bufferSize)) {
				throw new IllegalArgumentException("bufferSize must be a power of 2 : " + bufferSize);
			}

			if (bufferSize < 1){
				throw new IllegalArgumentException("bufferSize must be strictly positive, was: " + bufferSize);
			}
			this.bufferSize = bufferSize;
			return this;
		}

		/**
		 * Configures wait strategy for this builder. Default value is {@link WaitStrategy#phasedOffLiteLock(long, long, TimeUnit)}.
		 * Wait strategy is reset to default if the provided {@code waitStrategy} is null.
		 *
		 * @param waitStrategy A RingBuffer {@link WaitStrategy} to use instead of the default blocking wait strategy.
		 * @return builder with provided wait strategy
		 */
		public FanOutProcessorBuilder waitStrategy(@Nullable WaitStrategy waitStrategy) {
			this.waitStrategy = waitStrategy;
			return this;
		}

		/**
		 * Configures auto-cancel for this builder. Default value is {@code true}.
		 *
		 * @param autoCancel true to automatically cancel when all subscribers have cancelled.
		 * @return builder with provided auto-cancel
		 */
		public FanOutProcessorBuilder autoCancel(boolean autoCancel) {
			this.autoCancel = autoCancel;
			return this;
		}

		/**
		 * Configures an {@link ExecutorService} that will be used to back the thread-per-subscriber
		 * model of this fan out processor. The threads are named according to the executor's
		 * naming policy, which overrides name configuration made through {@link #name(String)}.
		 *
		 * @param executor A provided ExecutorService to manage threading infrastructure
		 * @return builder with provided executor
		 */
		public FanOutProcessorBuilder executor(@Nullable ExecutorService executor) {
			this.executor = executor;
			return this;
		}


		/**
		 * Configures an additional {@link ExecutorService} that is used internally
		 * on each subscription to perform the request.
		 *
		 * @param requestTaskExecutor internal request executor
		 * @return builder with provided internal request executor
		 */
		public FanOutProcessorBuilder requestTaskExecutor(@Nullable ExecutorService requestTaskExecutor) {
			this.requestTaskExecutor = requestTaskExecutor;
			return this;
		}

		/**
		 * Configures sharing state for this builder. A shared fanout processor authorizes
		 * is suited for multi-threaded publisher that will fan-in data.
		 *
		 * @param share true to support concurrent sources on the {@link ProcessorSink#asProcessor Processor}
		 * @return builder with specified sharing
		 */
		public FanOutProcessorBuilder share(boolean share) {
			this.share = share;
			return this;
		}

		/**
		 * Set an {@link OverflowStrategy} to use when creating the {@link FluxSink}
		 * backing this {@link FluxProcessorSink}.
		 *
		 * @param overflowStrategy the strategy to use on sink methods
		 * @return the builder
		 */
		public FanOutProcessorBuilder withOverflowStrategy(OverflowStrategy overflowStrategy) {
			this.overflowStrategy = overflowStrategy;
			return this;
		}

		/**
		 * Creates a new fanout {@link ProcessorSink} according to the builder's
		 * configuration.
		 *
		 * @return a new fanout {@link ProcessorSink}
		 */
		public <T> FluxProcessorSink<T>  build() {
			this.name = this.name != null ? this.name : "fanOut";
			this.waitStrategy = this.waitStrategy != null ? this.waitStrategy : WaitStrategy.phasedOffLiteLock(200, 100, TimeUnit.MILLISECONDS);
			EventLoopProcessor.EventLoopFactory threadFactory = this.executor != null
					? null
					: new EventLoopProcessor.EventLoopFactory(name, autoCancel);

			String requestTaskExecutorName = threadFactory != null
					? threadFactory.get()
					: "fanOutRequest";

			ExecutorService requestTaskExecutor = this.requestTaskExecutor != null
					? this.requestTaskExecutor
					: EventLoopProcessor.defaultRequestTaskExecutor(requestTaskExecutorName);

			EventLoopProcessor<T> processor;
			if (useWorkQueueProcessor) {
				processor = new WorkQueueProcessor<>(
						threadFactory,
						executor,
						requestTaskExecutor,
						bufferSize,
						waitStrategy,
						share,
						autoCancel);
			}
			else {
				processor = new TopicProcessor<>(
						threadFactory,
						executor,
						requestTaskExecutor,
						bufferSize,
						waitStrategy,
						share,
						autoCancel,
						null);
			}

			return new AsyncFluxProcessorSinkAdapter<>(processor, overflowStrategy);
		}
	}

	/**
	 * A builder for the {@link #first()} flavor of {@link ProcessorSink},
	 * directly attached to a {@link Publisher} source.
	 *
	 * @param <T>
	 */
	public static final class MonoFirstProcessorBuilder<T> {

		private final Publisher<? extends T> source;

		public MonoFirstProcessorBuilder(Publisher<? extends T> source) {
			this.source = source;
		}

		/**
		 * Create the processor with a {@link WaitStrategy} and attach it to the source.
		 *
		 * @param waitStrategy the {@link WaitStrategy} to use for blocking methods of the processor
		 * @return a new "first" {@link ProcessorSink} with a wait strategy, that
		 * is attached to a source
		 */
		public ProcessorSink<T> withWaitStrategy(WaitStrategy waitStrategy) {
			MonoProcessor<T> processor = new MonoProcessor<>(this.source, waitStrategy);
			return new MonoFirstProcessorSinkAdapter<>(processor);
		}

		/**
		 * Create the {@link ProcessorSink} by immediately attaching it to the
		 * {@link Publisher} source.
		 *
		 * @return a new first {@link ProcessorSink} that is attached to a source
		 */
		public MonoProcessorSink<T> build() {
			MonoProcessor<T> processor = new MonoProcessor<>(source);
			return new MonoFirstProcessorSinkAdapter<>(processor);
		}
	}

	//=== Adapter Classes from FluxProcessor to FluxProcessorSink ===

	static class FluxProcessorSinkAdapter<T> implements FluxProcessorSink<T> {

		final FluxProcessor<T, T> processor;
		final FluxSink<T> sink;
		final OverflowStrategy overflowStrategy;

		public FluxProcessorSinkAdapter(FluxProcessor<T,T> processor,
				@Nullable OverflowStrategy overflowStrategy) {
			this.processor = processor;
			this.overflowStrategy = overflowStrategy == null ? OverflowStrategy.IGNORE : overflowStrategy;
			this.sink = processor.sink(this.overflowStrategy);
		}

		@Override
		public Processor<T, T> asProcessor() {
			return processor;
		}

		@Override
		public Flux<T> asFlux() {
			return processor;
		}

		@Override
		public OverflowStrategy getOverflowStrategy() {
			return this.overflowStrategy;
		}

		// == delegates to FluxSink ==

		@Override
		public void complete() {
			sink.complete();
		}

		@Override
		public Context currentContext() {
			return sink.currentContext();
		}

		@Override
		public void error(Throwable e) {
			sink.error(e);
		}

		@Override
		public FluxSink<T> next(T t) {
			return sink.next(t);
		}

		@Override
		public long requestedFromDownstream() {
			return sink.requestedFromDownstream();
		}

		@Override
		public boolean isCancelled() {
			return sink.isCancelled();
		}

		@Override
		public FluxSink<T> onRequest(LongConsumer consumer) {
			return sink.onRequest(consumer);
		}

		@Override
		public FluxSink<T> onCancel(Disposable d) {
			return sink.onCancel(d);
		}

		@Override
		public FluxSink<T> onDispose(Disposable d) {
			return sink.onDispose(d);
		}

		//== delegates to FluxProcessor / processor

		@Override
		public void dispose() {
			processor.dispose();
		}

		@Override
		public boolean isDisposed() {
			return processor.isDisposed();
		}

		@Override
		public long getAvailableCapacity() {
			return processor.getAvailableCapacity();
		}

		@Override
		public long downstreamCount() {
			return processor.downstreamCount();
		}

		@Override
		@Nullable
		public Throwable getError() {
			return processor.getError();
		}

		@Override
		public boolean hasDownstreams() {
			return processor.hasDownstreams();
		}

		@Override
		public boolean isTerminated() {
			return processor.isTerminated();
		}

		@Override
		public boolean isSerialized() {
			return processor.isSerialized();
		}

	}

	static final class AsyncFluxProcessorSinkAdapter<T> extends FluxProcessorSinkAdapter<T> {

		public AsyncFluxProcessorSinkAdapter(EventLoopProcessor<T> processor,
				OverflowStrategy overflowStrategy) {
			super(processor, overflowStrategy);
		}

		@Override
		public Flux<T> forceDispose() {
			return ((EventLoopProcessor<T>) processor).forceShutdown();
		}

		@Override
		public boolean disposeAndAwait(Duration timeout) {
			return ((EventLoopProcessor<T>) processor).awaitAndShutdown(timeout);
		}
	}

	static class MonoFirstProcessorSinkAdapter<T> implements MonoProcessorSink<T> {

		final MonoProcessor<T> processor;
		final MonoSink<T> sink;

		public MonoFirstProcessorSinkAdapter(MonoProcessor<T> processor) {
			this.processor = processor;
			this.sink = new MonoCreate.DefaultMonoSink<>(processor);
		}

		@Override
		public Mono<T> asMono() {
			return processor;
		}

		@Override
		public Processor<T, T> asProcessor() {
			return processor;
		}

		@Override
		public long getAvailableCapacity() {
			if (isTerminated()) {
				return 0L;
			}
			return 1L;
		}

		@Override
		public boolean isSerialized() {
			return false;
		}

		//== delegates to sink ==

		@Override
		public Context currentContext() {
			return sink.currentContext();
		}

		@Override
		public void success() {
			sink.success();
		}

		@Override
		public void success(T value) {
			sink.success(value);
		}

		@Override
		public void error(Throwable e) {
			sink.error(e);
		}

		@Override
		public MonoSink<T> onRequest(LongConsumer consumer) {
			return sink.onRequest(consumer);
		}

		@Override
		public MonoSink<T> onCancel(Disposable d) {
			return sink.onCancel(d);
		}

		@Override
		public MonoSink<T> onDispose(Disposable d) {
			return sink.onDispose(d);
		}

		//==delegates to processor ==

		@Override
		public boolean isComplete() {
			return processor.isSuccess();
		}

		@Override
		public boolean isValued() {
			return processor.isValued();
		}

		@Override
		public boolean isTerminated() {
			return processor.isTerminated();
		}

		@Override
		public Throwable getError() {
			return processor.getError();
		}

		@Override
		public boolean isError() {
			return processor.isError();
		}

		@Override
		public void dispose() {
			processor.dispose();
		}

		@Override
		public boolean isDisposed() {
			return processor.isDisposed();
		}

		@Override
		public long downstreamCount() {
			return processor.downstreamCount();
		}

		@Override
		public boolean hasDownstreams() {
			return processor.hasDownstreams();
		}
	}
}
