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

import org.junit.Test;
import reactor.core.Scannable;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

public class ParallelLogTest {

	@Test
	public void parallelism() {
		ParallelFlux<Integer> source = Flux.range(1, 4).parallel(3);
		ParallelLog<Integer> test = new ParallelLog<>(source, null);

		assertThat(test.parallelism())
				.isEqualTo(3)
				.isEqualTo(source.parallelism());
	}

	@Test
	public void scanOperator() throws Exception {
		ParallelFlux<Integer> source = Flux.range(1, 4).parallel(3);
		ParallelLog<Integer> test = new ParallelLog<>(source, null);

		assertThat(test.scan(Scannable.Attr.PARENT)).isSameAs(source);
		assertThat(test.scan(Scannable.Attr.PREFETCH))
				.isEqualTo(256)
				.isEqualTo(source.getPrefetch());
	}

	@Test
	public void conditional() {
		Flux<Integer> source = Flux.range(1, 1_000);
		for (int i = 1; i < 33; i++) {
			Flux<Integer> result = ParallelFlux.from(source, i)
			                                   .log()
			                                   .filter(t -> true)
			                                   .sequential();

			StepVerifier.create(result)
			            .expectNextCount(1_000)
			            .verifyComplete();
		}
	}
}