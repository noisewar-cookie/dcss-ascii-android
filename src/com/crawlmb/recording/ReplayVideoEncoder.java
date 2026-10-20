package com.crawlmb.recording;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Encodes a recording into an H.264 MP4 from frames captured off the replay
 * views, at the game panel's size. Variable frame rate: a frame is submitted
 * only when the screen changes (plus a keep-alive each second), with exact
 * timestamps via EGL.
 * Runs on a background thread.
 */
public final class ReplayVideoEncoder
{
	public interface Progress
	{
		void update(int done, int total);
		boolean cancelled();
	}

	/** Screen images by timeline position, all one size. Encoder thread. */
	public interface Frames
	{
		int width();
		int height();
		/** Size of the bitmaps frame() returns; the screen is scaled to fit. */
		void setOutputSize(int w, int h);
		Bitmap frame(int pos) throws IOException;
	}

	private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
	private static final int FPS = 30;
	private static final long KEEPALIVE_MS = 1000;
	private static final long END_HOLD_MS = 2000;
	private static final int EGL_RECORDABLE_ANDROID = 0x3142;
	private static final int MAX_PIXELS = 1920 * 1088;

	private ReplayVideoEncoder() {}

	/** Video length in ms at each speed (as played, idle capped like replay). */
	public static long[] estimateMs(File src, double[] speeds)
	{
		SqliteRecordingStore store = SqliteRecordingStore.openForReplay(src);
		try
		{
			ReplayTimeline tl = timeline(store);
			ReplayClock clock = new ReplayClock();
			double ms = 0;
			for (int i = 1; i < tl.size(); i++)
				ms += clock.cost(tl, i - 1, i);
			long[] out = new long[speeds.length];
			for (int i = 0; i < speeds.length; i++)
				out[i] = (long) (ms / speeds[i]) + END_HOLD_MS;
			return out;
		}
		finally
		{
			store.close();
		}
	}

	static ReplayTimeline timeline(SqliteRecordingStore store)
	{
		return new ReplayTimeline(store.frameIndex(), store.events(), false);
	}

	/** The whole recording, as played. */
	public static void encode(File src, File out, double speed, Frames frames,
			Progress progress) throws IOException
	{
		encode(src, 0, Integer.MAX_VALUE, ReplayClock.Mode.AS_PLAYED, speed, frames, out,
				progress);
	}

	/** Recorded frames fromFrame..toFrame, paced like the replay clock in mode. */
	public static void encode(File src, int fromFrame, int toFrame, ReplayClock.Mode mode,
			double speed, Frames frames, File out, Progress progress) throws IOException
	{
		SqliteRecordingStore store = SqliteRecordingStore.openForReplay(src);
		Encoder enc = null;
		boolean ok = false;
		try
		{
			ReplayTimeline tl = timeline(store);
			if (tl.size() == 0)
				throw new IOException("recording is empty");
			int from = Math.max(0, tl.posOfFrame(fromFrame)), to = tl.posOfFrame(toFrame);
			if (to < from)
				throw new IOException("nothing to clip");
			ChunkCache cache = new ChunkCache(store, 2);
			enc = new Encoder(out, frames.width(), frames.height());
			frames.setOutputSize(enc.width, enc.height);
			ReplayClock clock = new ReplayClock();
			clock.setMode(mode);
			clock.setSpeed(speed);

			int pos = from;
			long frameNo = 0, videoMs = 0, lastSubmitMs = 0;
			ScreenState shown = cache.state(tl.chunkId(from), tl.frame(from));
			Bitmap bm = frames.frame(from);
			enc.submit(bm, true, 0);
			while (pos < to)
			{
				if (progress.cancelled())
					throw new IOException("cancelled");
				frameNo++;
				long t = frameNo * 1000 / FPS;
				pos = Math.min(to, clock.advance(tl, pos, t - videoMs));
				videoMs = t;
				ScreenState s = cache.state(tl.chunkId(pos), tl.frame(pos));
				if (s != shown && !(s.gridEquals(shown) && s.sideChannelsEqual(shown)))
				{
					shown = s;
					bm = frames.frame(pos);
					enc.submit(bm, true, videoMs);
					lastSubmitMs = videoMs;
				}
				else if (videoMs - lastSubmitMs >= KEEPALIVE_MS)
				{
					enc.submit(bm, false, videoMs);
					lastSubmitMs = videoMs;
				}
				if ((frameNo & 63) == 0)
					progress.update(pos - from, to - from);
			}
			// Hold the final screen (death/win, clip end) so it doesn't flash past. The
			// near-duplicate pins the last sample's muxed duration to one frame.
			enc.submit(bm, false, videoMs + END_HOLD_MS - 1000 / FPS);
			enc.submit(bm, false, videoMs + END_HOLD_MS);
			enc.finish();
			enc.release();
			enc = null;
			GameIdentity id = SqliteRecordingStore.identity(src);
			Mp4Tags.write(out, id == null ? null : id.name + " - DCSS " + id.dcssVersion,
					id == null ? null : id.name + ", Dungeon Crawl Stone Soup " + id.dcssVersion);
			ok = true;
		}
		finally
		{
			if (enc != null)
				enc.release();
			store.close();
			if (!ok)
				out.delete();
		}
	}

	/** MediaCodec surface input fed by a GLES2 textured quad, muxed to MP4. */
	private static final class Encoder
	{
		final int width, height;
		private final MediaCodec codec;
		private MediaMuxer muxer;
		private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
		private Surface input;
		private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
		private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
		private EGLSurface surface = EGL14.EGL_NO_SURFACE;
		private int track = -1;
		private boolean muxing;
		private int samples;
		private int texture;
		// Client-side vertex array: GL reads it at every draw, so it must outlive
		// setUpGl. A local was collected mid-export, smearing or freezing the video.
		private FloatBuffer quad;

		Encoder(File out, int srcWidth, int srcHeight) throws IOException
		{
			codec = MediaCodec.createEncoderByType(MIME);
			MediaCodecInfo.CodecCapabilities caps =
					codec.getCodecInfo().getCapabilitiesForType(MIME);
			MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
			int[] size = fit(vc, srcWidth, srcHeight);
			if (size == null)
			{
				codec.release();
				throw new IOException("no video size fits " + srcWidth + "x" + srcHeight);
			}
			width = size[0];
			height = size[1];
			MediaFormat f = MediaFormat.createVideoFormat(MIME, width, height);
			f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
					MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
			// Sharp text on mostly static frames: generous bitrate, sparse keyframes.
			f.setInteger(MediaFormat.KEY_BIT_RATE, vc.getBitrateRange().clamp(
					(int) Math.min(Integer.MAX_VALUE, 2.5 * width * height)));
			f.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
			f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
			if (caps.getEncoderCapabilities().isBitrateModeSupported(
					MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
				f.setInteger(MediaFormat.KEY_BITRATE_MODE,
						MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
			try
			{
				codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
				input = codec.createInputSurface();
				codec.start();
				muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
				setUpGl();
			}
			catch (IOException | RuntimeException e)
			{
				release();
				throw e;
			}
		}

		// Largest 16-aligned scale of the screen the encoder takes, capped at
		// 1080p's area: larger sizes the caps accept can still encode nothing.
		private static int[] fit(MediaCodecInfo.VideoCapabilities vc, int w, int h)
		{
			for (double s = 1; s > 0.2; s *= 0.9)
			{
				int fw = (int) (w * s) & ~15, fh = (int) (h * s) & ~15;
				if (fw >= 16 && fh >= 16 && fw * fh <= MAX_PIXELS
						&& vc.areSizeAndRateSupported(fw, fh, FPS))
					return new int[] { fw, fh };
			}
			return null;
		}

		private void setUpGl()
		{
			dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
			int[] v = new int[2];
			if (!EGL14.eglInitialize(dpy, v, 0, v, 1))
				throw new RuntimeException("eglInitialize failed");
			int[] attrs = {
					EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
					EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
					EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE };
			EGLConfig[] cfg = new EGLConfig[1];
			int[] n = new int[1];
			if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfg, 0, 1, n, 0) || n[0] == 0)
				throw new RuntimeException("no recordable EGL config");
			ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT,
					new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
			surface = EGL14.eglCreateWindowSurface(dpy, cfg[0], input,
					new int[] { EGL14.EGL_NONE }, 0);
			if (ctx == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE
					|| !EGL14.eglMakeCurrent(dpy, surface, surface, ctx))
				throw new RuntimeException("EGL setup failed");

			int prog = program(
					"attribute vec2 p; attribute vec2 t; varying vec2 v;"
					+ " void main() { gl_Position = vec4(p, 0.0, 1.0); v = t; }",
					"precision mediump float; varying vec2 v; uniform sampler2D s;"
					+ " void main() { gl_FragColor = texture2D(s, v); }");
			GLES20.glUseProgram(prog);
			// Strip BL, BR, TL, TR; bitmap row 0 is t=0, the top of the frame.
			quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
					.asFloatBuffer().put(new float[] {
							-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0 });
			int p = GLES20.glGetAttribLocation(prog, "p");
			int t = GLES20.glGetAttribLocation(prog, "t");
			quad.position(0);
			GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 16, quad);
			quad.position(2);
			GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 16, quad);
			GLES20.glEnableVertexAttribArray(p);
			GLES20.glEnableVertexAttribArray(t);

			int[] tex = new int[1];
			GLES20.glGenTextures(1, tex, 0);
			texture = tex[0];
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
			GLES20.glViewport(0, 0, width, height);
		}

		private static int program(String vs, String fs)
		{
			int prog = GLES20.glCreateProgram();
			GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs));
			GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs));
			GLES20.glLinkProgram(prog);
			int[] ok = new int[1];
			GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0);
			if (ok[0] == 0)
				throw new RuntimeException("GL link: " + GLES20.glGetProgramInfoLog(prog));
			return prog;
		}

		private static int shader(int type, String src)
		{
			int s = GLES20.glCreateShader(type);
			GLES20.glShaderSource(s, src);
			GLES20.glCompileShader(s);
			int[] ok = new int[1];
			GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
			if (ok[0] == 0)
				throw new RuntimeException("GL compile: " + GLES20.glGetShaderInfoLog(s));
			return s;
		}

		void submit(Bitmap frame, boolean changed, long ms)
		{
			if (changed)
				GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0);
			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
			EGLExt.eglPresentationTimeANDROID(dpy, surface, ms * 1_000_000L);
			EGL14.eglSwapBuffers(dpy, surface);
			drain(false);
		}

		void finish() throws IOException
		{
			codec.signalEndOfInputStream();
			drain(true);
			if (samples == 0)
				throw new IOException("the encoder produced no video at " + width + "x" + height);
			muxing = false;
			muxer.stop(); // writes the index; a failure here leaves an unplayable file
		}

		private void drain(boolean toEnd)
		{
			while (true)
			{
				int i = codec.dequeueOutputBuffer(info, toEnd ? 10_000 : 0);
				if (i == MediaCodec.INFO_TRY_AGAIN_LATER)
				{
					if (!toEnd)
						return;
				}
				else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
				{
					track = muxer.addTrack(codec.getOutputFormat());
					muxer.start();
					muxing = true;
				}
				else if (i >= 0)
				{
					ByteBuffer buf = codec.getOutputBuffer(i);
					if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
							&& info.size > 0 && muxing && buf != null)
					{
						buf.position(info.offset);
						buf.limit(info.offset + info.size);
						muxer.writeSampleData(track, buf, info);
						samples++;
					}
					codec.releaseOutputBuffer(i, false);
					if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
						return;
				}
			}
		}

		void release()
		{
			if (dpy != EGL14.EGL_NO_DISPLAY)
			{
				EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
						EGL14.EGL_NO_CONTEXT);
				if (surface != EGL14.EGL_NO_SURFACE)
					EGL14.eglDestroySurface(dpy, surface);
				if (ctx != EGL14.EGL_NO_CONTEXT)
					EGL14.eglDestroyContext(dpy, ctx);
				EGL14.eglReleaseThread();
				EGL14.eglTerminate(dpy);
				dpy = EGL14.EGL_NO_DISPLAY;
			}
			try
			{
				codec.stop();
			}
			catch (RuntimeException ignored)
			{
				// never started
			}
			codec.release();
			if (input != null)
				input.release();
			if (muxer != null)
			{
				try
				{
					if (muxing)
						muxer.stop();
				}
				catch (RuntimeException ignored)
				{
					// no samples written
				}
				muxer.release();
			}
		}
	}
}
