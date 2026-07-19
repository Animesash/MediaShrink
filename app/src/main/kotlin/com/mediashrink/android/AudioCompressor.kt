package com.mediashrink.android

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

class AudioCompressor(private val context: Context) {

    interface ProgressListener {
        fun onProgress(percent: Int)
        fun onComplete(outputUri: Uri, displayName: String)
        fun onError(error: Exception)
    }

    fun compress(
        inputUri: Uri,
        outputPfd: ParcelFileDescriptor,
        outputUri: Uri,
        displayName: String,
        targetBitrate: Int,
        listener: ProgressListener
    ) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, inputUri, null)

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || inputFormat == null) {
                throw Exception("Аудиодорожка не найдена")
            }

            extractor.selectTrack(audioTrackIndex)

            val duration = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
                inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val outputFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var muxerTrackIndex = -1
            var muxerStarted = false
            val decBufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val timeoutUs = 10000L

            // Вспомогательная функция: слить всё, что готово на выходе энкодера
            fun drainEncoderOutput(): Boolean {
                var reachedEos = false
                var draining = true
                while (draining) {
                    val encOutIndex = encoder!!.dequeueOutputBuffer(encBufferInfo, 0L)
                    when {
                        encOutIndex >= 0 -> {
                            val encodedData: ByteBuffer = encoder.getOutputBuffer(encOutIndex)!!

                            if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                encBufferInfo.size = 0
                            }

                            if (encBufferInfo.size > 0 && muxerStarted) {
                                encodedData.position(encBufferInfo.offset)
                                encodedData.limit(encBufferInfo.offset + encBufferInfo.size)
                                muxer!!.writeSampleData(muxerTrackIndex, encodedData, encBufferInfo)
                            }

                            encoder.releaseOutputBuffer(encOutIndex, false)

                            if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                reachedEos = true
                                draining = false
                            }
                        }
                        encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxerTrackIndex = muxer!!.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        else -> draining = false // INFO_TRY_AGAIN_LATER или прочее
                    }
                }
                return reachedEos
            }

            // Отправить один PCM-буфер декодера в энкодер, разбивая на подходящие порции
            fun feedEncoderFromDecoderBuffer(
                srcBuffer: ByteBuffer,
                offset: Int,
                size: Int,
                presentationTimeUs: Long,
                isEos: Boolean
            ) {
                srcBuffer.position(offset)
                srcBuffer.limit(offset + size)

                var remaining = size

                while (remaining > 0) {
                    var encInIndex = encoder!!.dequeueInputBuffer(timeoutUs)

                    // Если свободного буфера нет — сливаем выход энкодера и пробуем снова
                    var attempts = 0
                    while (encInIndex < 0 && attempts < 50) {
                        drainEncoderOutput()
                        encInIndex = encoder.dequeueInputBuffer(timeoutUs)
                        attempts++
                    }

                    if (encInIndex < 0) {
                        // Не удалось получить буфер — пропускаем этот кусок,
                        // чтобы не зависнуть намертво (крайний случай)
                        break
                    }

                    val encInBuffer = encoder.getInputBuffer(encInIndex)!!
                    encInBuffer.clear()

                    val chunkSize = minOf(remaining, encInBuffer.capacity())
                    val oldLimit = srcBuffer.limit()
                    srcBuffer.limit(srcBuffer.position() + chunkSize)
                    encInBuffer.put(srcBuffer)
                    srcBuffer.limit(oldLimit)

                    remaining -= chunkSize

                    val flags = if (isEos && remaining <= 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    encoder.queueInputBuffer(encInIndex, 0, chunkSize, presentationTimeUs, flags)

                    // Периодически сливаем выход, чтобы не забить буферы энкодера
                    drainEncoderOutput()
                }
            }

            while (!outputDone) {

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()

                            if (duration > 0) {
                                val progress = ((pts * 100) / duration).toInt().coerceIn(0, 99)
                                listener.onProgress(progress)
                            }
                        }
                    }
                }

                var decoderDrained = false
                while (!decoderDrained) {
                    val outIndex = decoder.dequeueOutputBuffer(decBufferInfo, timeoutUs)
                    when {
                        outIndex >= 0 -> {
                            val outBuffer = decoder.getOutputBuffer(outIndex)!!
                            val isEos = decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                            if (decBufferInfo.size > 0) {
                                feedEncoderFromDecoderBuffer(
                                    outBuffer,
                                    decBufferInfo.offset,
                                    decBufferInfo.size,
                                    decBufferInfo.presentationTimeUs,
                                    isEos
                                )
                            } else if (isEos) {
                                // Пустой буфер с флагом EOS — отправляем EOS явно
                                var encInIndex = encoder.dequeueInputBuffer(timeoutUs)
                                var attempts = 0
                                while (encInIndex < 0 && attempts < 50) {
                                    drainEncoderOutput()
                                    encInIndex = encoder.dequeueInputBuffer(timeoutUs)
                                    attempts++
                                }
                                if (encInIndex >= 0) {
                                    encoder.queueInputBuffer(encInIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                }
                            }

                            decoder.releaseOutputBuffer(outIndex, false)

                            if (isEos) {
                                decoderDrained = true
                            }
                        }
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> decoderDrained = true
                        else -> decoderDrained = true
                    }
                }

                // Финальный слив энкодера — проверяем, не пришёл ли EOS
                if (drainEncoderOutput()) {
                    outputDone = true
                }
            }

            listener.onProgress(100)
            listener.onComplete(outputUri, displayName)

        } catch (e: Exception) {
            android.util.Log.e("AudioCompressor", "Compression failed", e)
            listener.onError(e)
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
            try { outputPfd.close() } catch (_: Exception) {}
            extractor?.release()
        }
    }
}