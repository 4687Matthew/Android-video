package org.video.utils;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.util.Log;

import org.video.MainActivity;

import java.nio.ByteBuffer;

class BufferedVideoEditor {
    private static final String TAG = "BufferedVideoEditor";
    private static final BufferPoolManager bufferPool = BufferPoolManager.getInstance();

    /**
     * 使用缓冲区池的视频复制方法
     */
    public static boolean copySegmentWithBufferPool(MediaExtractor extractor,
                                                    int videoTrackIndex, int audioTrackIndex,
                                                    long startTimeUs, long endTimeUs,
                                                    MediaMuxer muxer,
                                                    int muxerVideoTrack, int muxerAudioTrack,
                                                    MainActivity.ProgressCallback callback) {
        ByteBuffer videoBuffer = null;
        ByteBuffer audioBuffer = null;

        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // 处理视频轨道
            extractor.selectTrack(videoTrackIndex);
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            long firstPts = -1;
            int videoFrames = 0;

            // 从缓冲区池获取视频缓冲区
            videoBuffer = bufferPool.getBuffer(BufferPoolManager.SIZE_4MB, "video_copy");

            while (true) {
                int sampleSize = extractor.readSampleData(videoBuffer, 0);
                if (sampleSize < 0) break;

                long pts = extractor.getSampleTime();
                if (pts >= endTimeUs) break;

                if (firstPts == -1) firstPts = pts;

                videoBuffer.position(0);
                videoBuffer.limit(sampleSize);

                long relativePts = pts - firstPts;
                info.set(0, sampleSize, relativePts,
                        VideoUtils.convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));

                muxer.writeSampleData(muxerVideoTrack, videoBuffer, info);
                videoFrames++;
                extractor.advance();

                // 更新进度
                if (videoFrames % 30 == 0 && callback != null) {
                    updateProgress(callback, "处理视频帧", videoFrames);
                }
            }

            Log.d(TAG, String.format("视频处理完成: %d帧", videoFrames));

            // 处理音频轨道（如果有）
            if (audioTrackIndex != -1 && muxerAudioTrack != -1) {
                extractor.unselectTrack(videoTrackIndex);
                extractor.selectTrack(audioTrackIndex);
                extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                int audioFrames = 0;
                long audioFirstPts = -1;

                // 从缓冲区池获取音频缓冲区（更小的缓冲区）
                audioBuffer = bufferPool.getBuffer(BufferPoolManager.SIZE_1MB, "audio_copy");

                while (true) {
                    int sampleSize = extractor.readSampleData(audioBuffer, 0);
                    if (sampleSize < 0) break;

                    long pts = extractor.getSampleTime();
                    if (pts >= endTimeUs) break;

                    if (audioFirstPts == -1) audioFirstPts = Math.max(pts, firstPts);

                    audioBuffer.position(0);
                    audioBuffer.limit(sampleSize);

                    long relativePts = pts - audioFirstPts;
                    info.set(0, sampleSize, relativePts,
                            VideoUtils.convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));
                    muxer.writeSampleData(muxerAudioTrack, audioBuffer, info);

                    audioFrames++;
                    extractor.advance();
                }

                Log.d(TAG, String.format("音频处理完成: %d帧", audioFrames));
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "copySegmentWithBufferPool error", e);
            return false;
        } finally {
            // 将缓冲区返回池中
            if (videoBuffer != null) {
                bufferPool.returnBuffer(videoBuffer, "video_copy_final");
            }
            if (audioBuffer != null) {
                bufferPool.returnBuffer(audioBuffer, "audio_copy_final");
            }

            // 打印缓冲区池统计信息
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                bufferPool.printStats();
            }
        }
    }

    /**
     * 使用缓冲区池的高B帧视频处理方法
     */
    public static boolean copySegmentHighBFramesWithBufferPool(MediaExtractor extractor,
                                                               int videoTrackIndex, int audioTrackIndex,
                                                               long startTimeUs, long endTimeUs,
                                                               MediaMuxer muxer,
                                                               int muxerVideoTrack, int muxerAudioTrack,
                                                               MainActivity.ProgressCallback callback) {
        ByteBuffer buffer = null;

        try {
            Log.d(TAG, "开始高B帧视频处理（带缓冲区池）...");

            // 使用大缓冲区（16MB）
            buffer = bufferPool.getBuffer(BufferPoolManager.SIZE_16MB, "high_b_frame");
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // ========== 1. 处理视频轨道 ==========
            extractor.selectTrack(videoTrackIndex);
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            long firstVideoPts = -1;
            int videoFrames = 0;
            int skippedFrames = 0;

            // 直接处理视频帧
            while (true) {
                // 清空缓冲区
                buffer.clear();

                // 直接读取样本数据
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                long pts = extractor.getSampleTime();
                if (endTimeUs != Long.MAX_VALUE && pts >= endTimeUs) {
                    Log.d(TAG, "达到结束时间，停止视频处理: " + pts);
                    break;
                }

                if (firstVideoPts == -1) {
                    firstVideoPts = pts;
                    Log.d(TAG, "第一个视频PTS: " + pts + "us, 样本大小: " + sampleSize);
                }

                buffer.position(0);
                buffer.limit(sampleSize);

                long relativePts = pts - firstVideoPts;
                if (relativePts < 0) {
                    // 如果时间戳为负，跳过这一帧
                    Log.w(TAG, "跳过负时间戳帧: " + pts);
                    extractor.advance();
                    skippedFrames++;
                    continue;
                }

                info.set(0, sampleSize, relativePts,
                        VideoUtils.convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));

                try {
                    muxer.writeSampleData(muxerVideoTrack, buffer, info);
                    videoFrames++;

                    // 每100帧记录一次
                    if (videoFrames % 100 == 0) {
                        Log.d(TAG, String.format("已处理 %d 视频帧, 当前PTS: %.3fs",
                                videoFrames, relativePts/1000000.0));
                        updateProgress(callback, "处理高B帧视频", videoFrames);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "写入视频数据失败: " + e.getMessage(), e);
                    break;
                }

                if (!extractor.advance()) {
                    break;
                }
            }

            Log.d(TAG, String.format("视频处理完成: %d帧, 跳过%d帧", videoFrames, skippedFrames));

            // ========== 2. 处理音频轨道 ==========
            int audioFrames = 0;
            if (audioTrackIndex != -1 && muxerAudioTrack != -1) {
                Log.d(TAG, "开始处理音频轨道...");

                extractor.unselectTrack(videoTrackIndex);
                extractor.selectTrack(audioTrackIndex);

                // 音频应从视频起始时间开始
                extractor.seekTo(firstVideoPts, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                long audioStartPts = extractor.getSampleTime();

                Log.d(TAG, "音频起始PTS: " + audioStartPts + "us");

                // 如果音频起始时间为负，调整
                long audioOffset = 0;
                if (audioStartPts < 0) {
                    audioOffset = -audioStartPts;
                    Log.d(TAG, "音频初始负偏移: " + audioOffset + "us");
                }

                int skippedAudioFrames = 0;

                // 重置缓冲区用于音频
                buffer.clear();

                while (true) {
                    buffer.clear();
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;

                    long pts = extractor.getSampleTime();
                    if (endTimeUs != Long.MAX_VALUE && pts >= endTimeUs) {
                        break;
                    }

                    buffer.position(0);
                    buffer.limit(sampleSize);

                    // 音频时间戳对齐视频
                    long alignedPts = pts - firstVideoPts + audioOffset;
                    if (alignedPts < 0) {
                        // 跳过负时间戳的音频帧
                        Log.w(TAG, "跳过负时间戳音频帧: " + pts);
                        extractor.advance();
                        skippedAudioFrames++;
                        continue;
                    }

                    info.set(0, sampleSize, alignedPts,
                            VideoUtils.convertSampleFlagsToBufferFlags(extractor.getSampleFlags()));

                    try {
                        muxer.writeSampleData(muxerAudioTrack, buffer, info);
                        audioFrames++;

                        // 每200帧记录一次
                        if (audioFrames % 200 == 0) {
                            Log.d(TAG, String.format("已处理 %d 音频帧", audioFrames));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "写入音频数据失败: " + e.getMessage(), e);
                        break;
                    }

                    if (!extractor.advance()) {
                        break;
                    }
                }

                Log.d(TAG, String.format("音频处理完成: %d帧, 跳过%d帧",
                        audioFrames, skippedAudioFrames));

                // 恢复视频轨道选择
                extractor.unselectTrack(audioTrackIndex);
                extractor.selectTrack(videoTrackIndex);
            }

            if (callback != null) {
                callback.onProgressUpdate(
                        String.format("完成: %d视频帧, %d音频帧", videoFrames, audioFrames),
                        100);
            }

            return videoFrames > 0;

        } catch (Exception e) {
            Log.e(TAG, "copySegmentHighBFramesWithBufferPool error", e);
            return false;
        } finally {
            // 将缓冲区返回池中
            if (buffer != null) {
                bufferPool.returnBuffer(buffer, "high_b_frame_final");
            }

            // 打印缓冲区池统计信息
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "缓冲区池状态: " + bufferPool.getPoolStatus());
            }
        }
    }

    /**
     * 更新进度辅助方法
     */
    private static void updateProgress(MainActivity.ProgressCallback callback, String status, int frames) {
        if (callback != null) {
            callback.onProgressUpdate(String.format("%s: %d帧", status, frames), -1);
        }
    }
}
