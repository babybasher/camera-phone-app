package net.homecam.phone

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.webrtc.VideoFrame
import java.io.File
import java.nio.ByteBuffer

/**
 * Records the camera picture (and the microphone, when it is on) into an mp4
 * using the phone's own encoders. Frames come from the live camera feed, so
 * recording works whether or not anyone is watching.
 */
class Recorder(
    private val file: File,
    private val fps: Int,
    private val bitrate: Int,
    private val audioRate: Int,
    private val audioChannels: Int,
) {
    private val thread = HandlerThread("rec").apply { start() }
    private val h = Handler(thread.looper)
    private val startNs = System.nanoTime()
    private val withAudio = audioRate > 0
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var video: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var audio: MediaCodec? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var finished = false
    private var frames = 0
    private var audioSamples = 0L
    private val pending = ArrayList<Triple<Boolean, ByteBuffer, MediaCodec.BufferInfo>>()
    private val info = MediaCodec.BufferInfo()
    private val frameGapNs = 1_000_000_000L / fps.coerceAtLeast(1)
    private var lastFrameNs = 0L

    init {
        if (withAudio) {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, audioRate, audioChannels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            }
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
        }
    }

    /** Called on the camera thread. Takes its own copy of the frame. */
    fun addFrame(frame: VideoFrame) {
        val now = System.nanoTime()
        if (now - lastFrameNs < frameGapNs * 8 / 10) return
        lastFrameNs = now
        val buf = frame.buffer
        if (width != 0 && (buf.width != width || buf.height != height)) return
        val rotation = frame.rotation
        val i420 = buf.toI420() ?: return
        h.post {
            try {
                if (!finished) {
                    if (video == null) openVideo(i420.width, i420.height, rotation)
                    encodeFrame(i420, (now - startNs) / 1000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "video frame: $e")
            } finally {
                i420.release()
            }
        }
    }

    /** Called on the audio thread with 16-bit PCM. */
    fun addAudio(data: ByteArray) {
        if (!withAudio) return
        val copy = data.copyOf()
        h.post {
            try {
                if (!finished) encodeAudio(copy)
            } catch (e: Exception) {
                Log.w(TAG, "audio: $e")
            }
        }
    }

    /** The first frame decides the size and which way up the video is. */
    private fun openVideo(w: Int, h: Int, rotation: Int) {
        width = w
        height = h
        muxer.setOrientationHint(((rotation % 360) + 360) % 360)
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
    }

    private fun encodeFrame(b: VideoFrame.I420Buffer, ptsUs: Long) {
        val video = this.video ?: return
        if (b.width != width || b.height != height) return
        val idx = video.dequeueInputBuffer(20_000)
        if (idx < 0) return
        val img = video.getInputImage(idx)
        if (img == null) {
            video.queueInputBuffer(idx, 0, 0, ptsUs, 0)
            return
        }
        val planes = img.planes
        copyPlane(b.dataY, b.strideY, width, height, planes[0].buffer, planes[0].rowStride, planes[0].pixelStride)
        copyPlane(b.dataU, b.strideU, (width + 1) / 2, (height + 1) / 2, planes[1].buffer, planes[1].rowStride, planes[1].pixelStride)
        copyPlane(b.dataV, b.strideV, (width + 1) / 2, (height + 1) / 2, planes[2].buffer, planes[2].rowStride, planes[2].pixelStride)
        video.queueInputBuffer(idx, 0, width * height * 3 / 2, ptsUs, 0)
        frames++
        drain(video, true, false)
    }

    private fun copyPlane(src: ByteBuffer, srcStride: Int, w: Int, h: Int, dst: ByteBuffer, dstStride: Int, pixStride: Int) {
        val row = ByteArray(w)
        for (y in 0 until h) {
            src.position(y * srcStride)
            src.get(row, 0, w)
            if (pixStride == 1) {
                dst.position(y * dstStride)
                dst.put(row, 0, w)
            } else {
                val base = y * dstStride
                for (x in 0 until w) {
                    val at = base + x * pixStride
                    if (at < dst.limit()) dst.put(at, row[x])
                }
            }
        }
    }

    private fun encodeAudio(pcm: ByteArray) {
        val a = audio ?: return
        var off = 0
        while (off < pcm.size) {
            val idx = a.dequeueInputBuffer(20_000)
            if (idx < 0) return
            val buf = a.getInputBuffer(idx) ?: return
            buf.clear()
            val n = minOf(buf.remaining(), pcm.size - off)
            buf.put(pcm, off, n)
            val pts = audioSamples * 1_000_000L / audioRate
            audioSamples += n / (2 * audioChannels)
            a.queueInputBuffer(idx, 0, n, pts, 0)
            off += n
        }
        drain(a, false, false)
    }

    private fun drain(codec: MediaCodec, isVideo: Boolean, eos: Boolean) {
        var waits = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos || ++waits > 200) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (isVideo) videoTrack = muxer.addTrack(codec.outputFormat)
                    else audioTrack = muxer.addTrack(codec.outputFormat)
                    maybeStart(false)
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (out != null && info.size > 0 && !config) write(isVideo, out, info)
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun maybeStart(force: Boolean) {
        if (started || videoTrack < 0) return
        if (withAudio && audioTrack < 0 && !force) return
        muxer.start()
        started = true
        for ((v, b, i) in pending) {
            val t = if (v) videoTrack else audioTrack
            if (t >= 0) muxer.writeSampleData(t, b, i)
        }
        pending.clear()
    }

    private fun write(isVideo: Boolean, out: ByteBuffer, i: MediaCodec.BufferInfo) {
        if (started) {
            val t = if (isVideo) videoTrack else audioTrack
            if (t >= 0) muxer.writeSampleData(t, out, i)
            return
        }
        if (pending.size > 600) return
        val copy = ByteBuffer.allocate(i.size)
        out.position(i.offset)
        out.limit(i.offset + i.size)
        copy.put(out)
        copy.flip()
        val ci = MediaCodec.BufferInfo().apply { set(0, i.size, i.presentationTimeUs, i.flags) }
        pending.add(Triple(isVideo, copy, ci))
    }

    /** Finish the file; [done] gets the length in seconds, or 0 if nothing was recorded. */
    fun finish(done: (Int) -> Unit) {
        h.post {
            var secs = 0
            try {
                finished = true
                video?.let { endStream(it, true) }
                audio?.let { endStream(it, false) }
                maybeStart(true)
                if (started && frames > 0) {
                    muxer.stop()
                    secs = maxOf(1, ((System.nanoTime() - startNs) / 1_000_000_000L).toInt())
                }
            } catch (e: Exception) {
                Log.w(TAG, "finishing: $e")
            }
            try { video?.release() } catch (_: Exception) {}
            try { audio?.release() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            if (secs == 0) file.delete()
            thread.quitSafely()
            done(secs)
        }
    }

    private fun endStream(codec: MediaCodec, isVideo: Boolean) {
        val idx = codec.dequeueInputBuffer(50_000)
        if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, (System.nanoTime() - startNs) / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(codec, isVideo, true)
    }

    companion object { const val TAG = "Recorder" }
}
