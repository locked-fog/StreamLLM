package dev.lockedfog.streamllm.dsl

import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.core.StreamScope
import kotlinx.coroutines.runBlocking

fun stream(block: suspend StreamScope.() -> Unit) {
    runBlocking<Unit> {
        val scope = StreamScope()
        block(scope)
    }
}