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
import java.util.function.Consumer;

import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import reactor.core.Disposable;
import reactor.util.annotation.Nullable;

/**
 * @author Simon Baslé
 */
public interface ProcessorSink<T> extends Disposable {

	Processor<T, T> asProcessor();

	/**
	 * Terminate with the give exception
	 * <p>
	 * Calling this method multiple times or after the other terminating methods is
	 * an unsupported operation. It will discard the exception through the
	 * {@link Hooks#onErrorDropped(Consumer)} hook (which by default throws the exception
	 * wrapped via {@link reactor.core.Exceptions#bubble(Throwable)}). This is to avoid
	 * complete and silent swallowing of the exception.
	 *
	 * @param e the exception to complete with
	 */
	void error(Throwable e);

	/**
	 * Return the produced {@link Throwable} error if any or null
	 *
	 * @return the produced {@link Throwable} error if any or null
	 */
	@Nullable
	Throwable getError();

	/**
	 * Return true if terminated with onComplete
	 *
	 * @return true if terminated with onComplete
	 */
	default boolean isComplete() {
		return isTerminated() && getError() == null;
	}

	/**
	 * Return true if terminated with onError
	 *
	 * @return true if terminated with onError
	 */
	default boolean isError() {
		return isTerminated() && getError() != null;
	}

	/**
	 * Indicates whether this {@link ProcessorSink} has been terminated by the
	 * source producer with a success or an error.
	 *
	 * @return {@code true} if this {@link ProcessorSink} is successful, {@code false} otherwise.
	 */
	boolean isTerminated();

	/**
	 * Forcibly terminate the {@link ProcessorSink}, preventing it to be reused and
	 * resubscribed.
	 */
	@Override
	void dispose();

	/**
	 * Indicates whether this {@link ProcessorSink} has been terminated by calling its
	 * {@link #dispose()} method.
	 *
	 * @return true if the {@link ProcessorSink} has been terminated.
	 */
	@Override
	boolean isDisposed();


	/**
	 * For asynchronous {@link ProcessorSink}, which maintain heavy resources
	 * (such as {@link Processors#fanOut()}), this method attempts to forcibly shutdown
	 * these resources, unlike {@link #dispose()} which would let the {@link ProcessorSink}
	 * tear down the resources gracefully.
	 * <p>
	 * Since for asynchronous {@link ProcessorSink} there could be undistributed values at
	 * this point, said values are returned as a {@link Flux}.
	 * <p>
	 * For other implementations, this is equivalent to calling {@link #dispose()} and
	 * returns an {@link Flux#empty() empty Flux}.
	 *
	 * @return a {@link Flux} of the undistributed values for async {@link ProcessorSink Broadcasters}
	 */
	default Flux<T> forceDispose() {
		dispose();
		return Flux.empty();
	}

	/**
	 * For {@link ProcessorSink} that maintain heavy resources (such as {@link Processors#fanOut()}),
	 * this method attempts to shutdown these resources gracefully within the given {@link Duration}.
	 * Unlike {@link #dispose()}, this <strong>blocks</strong> for the given {@link Duration}.
	 *
	 * <p>
	 * For other implementations, this is equivalent to calling {@link #dispose()}, returning
	 * the result of {@link #isDisposed()} immediately.
	 *
	 * @param timeout the timeout value as a {@link java.time.Duration}. Note this is
	 * converted to a {@link Long} * of nanoseconds (which amounts to roughly 292 years
	 * maximum timeout).
	 * @return if the underlying executor terminated and false if the timeout elapsed before
	 * termination
	 */
	default boolean disposeAndAwait(Duration timeout) {
		dispose();
		return isDisposed();
	}

	/**
	 * @return a snapshot number of available onNext before starving the resource
	 */
	long getAvailableCapacity();

	/**
	 * Return the number of active {@link Subscriber} or {@literal -1} if untracked.
	 *
	 * @return the number of active {@link Subscriber} or {@literal -1} if untracked
	 */
	long downstreamCount();

	/**
	 * Return true if any {@link Subscriber} is actively subscribed
	 *
	 * @return true if any {@link Subscriber} is actively subscribed
	 */
	default boolean hasDownstreams() {
		return downstreamCount() != 0L;
	}

	/**
	 * Return true if this {@link ProcessorSink} supports multithread producing
	 *
	 * @return true if this {@link ProcessorSink} supports multithread producing
	 */
	boolean isSerialized();

}
