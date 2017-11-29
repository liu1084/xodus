/**
 * Copyright 2010 - 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.crypto

import jetbrains.exodus.log.LogUtil
import jetbrains.exodus.util.ByteArraySpinAllocator
import mu.KLogging
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class ScytaleEngine(
        private val listener: EncryptListener,
        private val cipherProvider: StreamCipherProvider,
        private val key: ByteArray,
        bufferSize: Int = 1024 * 1024,  // 1MB
        inputQueueSize: Int = 40,
        outputQueueSize: Int = 40
) : Closeable {
    companion object : KLogging() {
        val timeout = 200L
    }

    val inputQueue = ArrayBlockingQueue<EncryptMessage>(inputQueueSize)
    val outputQueue = ArrayBlockingQueue<EncryptMessage>(outputQueueSize)
    val bufferAllocator = ByteArraySpinAllocator(bufferSize, inputQueueSize + outputQueueSize + 4)

    @Volatile
    var producerFinished = false
    @Volatile
    var consumerFinished = false
    @Volatile
    var cancelled = false
    @Volatile
    var error: Throwable? = null

    private val producer = Thread({
        try {
            val cipher = cipherProvider.newCipher()
            var offset = 0
            var blockAddress = 0L
            while (!cancelled && error == null) {
                inputQueue.poll(timeout, TimeUnit.MILLISECONDS)?.let {
                    when (it) {
                        is FileHeader -> {
                            offset = 0
                            blockAddress = it.handle / LogUtil.LOG_BLOCK_ALIGNMENT
                            cipher.init(key, blockAddress.asHashedIV())
                        }
                        is FileChunk -> {
                            val header = it.header
                            if (header.canBeEncrypted) {
                                val data = it.data
                                for (i in 0 until it.size) {
                                    offset++
                                    data[i] = cipher.crypt(data[i])
                                    if (header.chunkedIV) {
                                        offset++
                                        if (offset == LogUtil.LOG_BLOCK_ALIGNMENT) {
                                            offset == 0
                                            blockAddress++
                                            cipher.init(key, blockAddress.asHashedIV())
                                        }
                                    }
                                }
                            }
                        }
                        is EndChunk -> {
                        }
                        else -> throw IllegalArgumentException()
                    }
                    while (!outputQueue.offer(it, timeout, TimeUnit.MILLISECONDS)) {
                        if (cancelled || error != null) {
                            return@Thread
                        }
                    }
                } ?: if (producerFinished) {
                    return@Thread
                }
            }
        } catch (t: Throwable) {
            producerFinished = true
            error = t
        }
    }, "xodus encrypt " + hashCode())

    private val consumer = Thread({
        try {
            var currentFile: FileHeader? = null
            while (!cancelled && error == null) {
                outputQueue.poll(timeout, TimeUnit.MILLISECONDS)?.let {
                    when (it) {
                        is FileHeader -> {
                            currentFile?.let {
                                listener.onFileEnd(it)
                            }
                            currentFile = it
                            listener.onFile(it)
                        }
                        is FileChunk -> {
                            val current = currentFile
                            if (current != null && current != it.header) {
                                throw Throwable("Invalid chunk with header " + it.header.path)
                            } else {
                                listener.onData(it.size, it.data)
                                bufferAllocator.dispose(it.data)
                            }
                        }
                        is EndChunk -> {
                            currentFile?.let {
                                listener.onFileEnd(it)
                            }
                        }
                        else -> throw IllegalArgumentException()
                    }
                } ?: if (consumerFinished) {
                    return@Thread
                }
            }
        } catch (t: Throwable) {
            consumerFinished = true
            error = t
        }
    }, "xodus write " + hashCode())

    fun start() {
        producer.start()
        consumer.start()
    }

    fun alloc(): ByteArray = bufferAllocator.alloc()

    fun put(e: EncryptMessage) {
        while (!inputQueue.offer(e, timeout, TimeUnit.MILLISECONDS)) {
            if (error != null) {
                throw RuntimeException(error)
            }
        }
    }

    fun cancel() {
        cancelled = true
    }

    override fun close() {
        producerFinished = true
        producer.join()
        consumerFinished = true
        consumer.join()
        error?.let { throw RuntimeException(it) }
    }
}