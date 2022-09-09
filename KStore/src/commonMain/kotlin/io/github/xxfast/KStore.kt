package io.github.xxfast

import io.github.xxfast.utils.FILE_SYSTEM
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.json.okio.encodeToBufferedSink
import okio.Path
import okio.buffer
import okio.use

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T : @Serializable Any> store(
  default: T? = null,
  path: Path,
  enableCache: Boolean = true,
  serializer: Json = Json,
): KStore<T> {
  val encoder: (T?) -> Unit = { value: T? ->
    FILE_SYSTEM.sink(path).buffer().use { serializer.encodeToBufferedSink(value, it) }
  }

  val decoder: () -> T? = { serializer.decodeFromBufferedSource(FILE_SYSTEM.source(path).buffer()) }
  return KStore(default, path, enableCache, encoder, decoder)
}

class KStore<T : @Serializable Any>(
  default: T? = null,
  private val path: Path,
  private val enableCache: Boolean = true,
  private val encoder: suspend (T?) -> Unit,
  private val decoder: suspend () -> T?,
) {
  private val lock: Mutex = Mutex()
  private val stateFlow: MutableStateFlow<T?> = MutableStateFlow(default)

  val updates: Flow<T?> get() = this.stateFlow

  private suspend fun write(value: T?){
    encoder.invoke(value)
    stateFlow.emit(value)
  }

  private suspend fun read(): T? {
    if (enableCache && stateFlow.value != null) return stateFlow.value
    val decoded: T? = try { decoder.invoke() } catch (e: Exception) { null }
    if (stateFlow.value == null && decoded != null) stateFlow.emit(decoded)
    return decoded
  }

  suspend fun set(value: T?) = lock.withLock { write(value) }
  suspend fun get(): T? = lock.withLock { read() }

  suspend fun update(operation: (T?) -> T?) = lock.withLock {
    val previous: T? = read()
    val updated: T? = operation(previous)
    write(updated)
  }

  suspend fun clear() {
    FILE_SYSTEM.delete(path)
    stateFlow.emit(null)
  }
}