/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import reactor.test.subscriber.AssertSubscriber;

import static org.assertj.core.api.Assertions.assertThat;

public class MonoRepeatWhenEmptyTest {

    @Test
    public void repeatInfinite() {
        AtomicInteger c = new AtomicInteger();

        Mono<String> source = Mono.defer(() -> c.getAndIncrement() < 3 ? Mono.empty() : Mono.just("test-data"));

        List<Long> iterations = new ArrayList<>();
        AssertSubscriber<String> ts = AssertSubscriber.create();

        source
            .repeatWhenEmpty(o -> o.doOnNext(iterations::add))
            .subscribe(ts);

        ts
            .assertValues("test-data")
            .assertComplete()
            .assertNoError();

        assertThat(c.get()).isEqualTo(4);
        assertThat(iterations).containsExactly(0L, 1L, 2L);
    }

    @Test
    public void repeatFinite() {
        AtomicInteger c = new AtomicInteger();

        Mono<String> source = Mono.defer(() -> c.getAndIncrement() < 3 ? Mono.empty() : Mono.just("test-data"));

        List<Long> iterations = new ArrayList<>();
        AssertSubscriber<String> ts = AssertSubscriber.create();

        source
            .repeatWhenEmpty(1000, o -> o.doOnNext(iterations::add))
            .subscribe(ts);

        ts
            .assertValues("test-data")
            .assertComplete()
            .assertNoError();

        assertThat(c).hasValue(4);
        assertThat(iterations).containsExactly(0L, 1L, 2L);
    }

    @Test
    public void repeatFiniteExceeded() {
        AtomicInteger c = new AtomicInteger();

        Mono<String> source = Mono.defer(() -> c.getAndIncrement() < 3 ? Mono.empty() : Mono.just("test-data"));

        List<Long> iterations = new ArrayList<>();
        AssertSubscriber<String> ts = AssertSubscriber.create();

        source
            .repeatWhenEmpty(2, o -> o.doOnNext(iterations::add))
            .subscribe(ts);

        ts
            .assertError(IllegalStateException.class);

        assertThat(c).hasValue(3);
        assertThat(iterations).containsExactly(0L, 1L);
    }

}
