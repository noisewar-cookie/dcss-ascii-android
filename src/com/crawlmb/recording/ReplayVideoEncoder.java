package com.crawlmb.recording;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
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
 * Renders a recording as the classic 80-column console into an H.264 MP4.
 * Variable frame rate: a frame is submitted only when the screen changes
 * (plus a keep-alive each second), with exact timestamps via EGL.
 * Runs on a background thread.
 */
public final class ReplayVideoEncoder
{
	public interface Progress
	{
		void update(int done, int total);
		boolean cancelled();
	}

	private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
	private static final int FPS = 30;
	private static final long KEEPALIVE_MS = 1000;
	private static final long END_HOLD_MS = 2000;
	private static final int EGL_RECORDABLE_ANDROID = 0x3142;
	// Native view_max_height (libandroid.cc); the message panel sits below it.
	private static final int GAMEPLAY_VIEW_LINES = 17;

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

	public static void encode(File src, File out, double speed, Typeface tf, Progress progress)
			throws IOException
	{
		SqliteRecordingStore store = SqliteRecordingStore.openForReplay(src);
		Encoder enc = null;
		boolean ok = false;
		try
		{
			ReplayTimeline tl = timeline(store);
			if (tl.size() == 0)
				throw new IOException("recording is empty");
			ChunkCache cache = new ChunkCache(store, 2);
			ConsolePainter painter = new ConsolePainter(tf);
			// Orientation from the first gameplay screen: portrait phone layouts
			// (tall message panel) would be tiny in a landscape frame.
			int first = 0;
			while (first < tl.size() - 1 && tl.turn(first) < 0)
				first++;
			enc = new Encoder(out, painter.isTall(cache.state(tl.chunkId(first), tl.frame(first))));
			painter.setSize(enc.width, enc.height);
			ReplayClock clock = new ReplayClock();
			clock.setSpeed(speed);

			int pos = 0;
			long frameNo = 0, videoMs = 0, lastSubmitMs = 0;
			ScreenState shown = cache.state(tl.chunkId(0), tl.frame(0));
			painter.paint(shown);
			enc.submit(painter.bitmap, true, 0);
			while (!clock.atEnd(tl, pos))
			{
				if (progress.cancelled())
					throw new IOException("cancelled");
				frameNo++;
				long t = frameNo * 1000 / FPS;
				pos = clock.advance(tl, pos, t - videoMs);
				videoMs = t;
				ScreenState s = cache.state(tl.chunkId(pos), tl.frame(pos));
				if (s != shown && !s.gridEquals(shown))
				{
					shown = s;
					painter.paint(s);
					enc.submit(painter.bitmap, true, videoMs);
					lastSubmitMs = videoMs;
				}
				else if (videoMs - lastSubmitMs >= KEEPALIVE_MS)
				{
					enc.submit(painter.bitmap, false, videoMs);
					lastSubmitMs = videoMs;
				}
				if ((frameNo & 63) == 0)
					progress.update(pos, tl.size() - 1);
			}
			// Hold the final screen (death/win) so it doesn't flash past. The
			// near-duplicate pins the last sample's muxed duration to one frame.
			enc.submit(painter.bitmap, false, videoMs + END_HOLD_MS - 1000 / FPS);
			enc.submit(painter.bitmap, false, videoMs + END_HOLD_MS);
			enc.finish();
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

	/** Draws a ScreenState as a centred cell grid on black. Shared with ReplayGifEncoder. */
	static final class ConsolePainter
	{
		Bitmap bitmap;
		private Canvas canvas;
		private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint fill = new Paint();
		private int width, height;
		private final float advancePerSize, spacingPerSize, ascentPerSize;

		ConsolePainter(Typeface tf)
		{
			text.setTypeface(tf);
			text.setTextSize(100);
			Paint.FontMetrics fm = text.getFontMetrics();
			advancePerSize = text.measureText("M") / 100;
			spacingPerSize = (fm.descent - fm.ascent) / 100;
			ascentPerSize = -fm.ascent / 100;
		}

		void setSize(int width, int height)
		{
			this.width = width;
			this.height = height;
			bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
			canvas = new Canvas(bitmap);
		}

		/** Width and height whose cells are cellW px wide at s's gameplay row count. */
		int[] sizeFor(ScreenState s, float cellW)
		{
			float size = cellW / advancePerSize;
			return new int[] { Math.round(s.cols * cellW), Math.round(rows(s) * spacingPerSize * size) };
		}

		boolean isTall(ScreenState s)
		{
			return rows(s) * spacingPerSize > s.cols * advancePerSize;
		}

		private static int rows(ScreenState s)
		{
			return Math.min(s.rows, Math.max(layoutRows(s), lastUsedRow(s) + 1));
		}

		/** Bottom pixel of s's last non-blank row when painted at the current size. */
		int usedHeight(ScreenState s)
		{
			int rows = rows(s);
			float ch = spacingPerSize * textSize(s, rows);
			float top = (height - ch * rows) / 2;
			return Math.min(height, (int) Math.ceil(top + (lastUsedRow(s) + 1) * ch));
		}

		private float textSize(ScreenState s, int rows)
		{
			float size = height / (rows * spacingPerSize);
			if (s.cols * advancePerSize * size > width)
				size = width / (s.cols * advancePerSize);
			return size;
		}

		void paint(ScreenState s)
		{
			int rows = rows(s);
			float size = textSize(s, rows);
			text.setTextSize(size);
			float cw = advancePerSize * size, ch = spacingPerSize * size;
			float left = (width - cw * s.cols) / 2, top = (height - ch * rows) / 2;
			float ascent = ascentPerSize * size;

			canvas.drawColor(Color.BLACK);
			for (int r = 0; r < rows; r++)
			{
				float y0 = top + r * ch;
				for (int c = 0; c < s.cols; c++)
				{
					int i = r * s.cols + c;
					float x0 = left + c * cw;
					if (!isBlack(s.bg[i]))
					{
						fill.setColor(s.bg[i] | 0xFF000000);
						canvas.drawRect(Math.round(x0), Math.round(y0),
								Math.round(x0 + cw), Math.round(y0 + ch), fill);
					}
					char k = s.chars[i];
					if (k > ' ')
					{
						text.setColor(s.fg[i] | 0xFF000000);
						canvas.drawText(s.chars, i, 1, x0, y0 + ascent, text);
					}
				}
			}
		}

		// Gameplay keeps a fixed scale; only taller menus shrink the cells.
		private static int layoutRows(ScreenState s)
		{
			int msgRows = s.layout[RecFormat.LAYOUT_MSG_ROWS];
			return msgRows > 0 ? GAMEPLAY_VIEW_LINES + msgRows : 24;
		}

		private static int lastUsedRow(ScreenState s)
		{
			for (int r = s.rows - 1; r >= 0; r--)
				for (int c = 0; c < s.cols; c++)
				{
					int i = r * s.cols + c;
					if (s.chars[i] > ' ' || !isBlack(s.bg[i]))
						return r;
				}
			return 0;
		}

		private static boolean isBlack(int argb)
		{
			return (argb & 0xFFFFFF) == 0;
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
		private int texture;

		Encoder(File out, boolean portrait) throws IOException
		{
			codec = MediaCodec.createEncoderByType(MIME);
			MediaCodecInfo.CodecCapabilities caps =
					codec.getCodecInfo().getCapabilitiesForType(MIME);
			MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
			// Some encoders cap height below width; fall back to landscape.
			if (portrait && !vc.isSizeSupported(1080, 1920) && !vc.isSizeSupported(720, 1280))
				portrait = false;
			int big = 1920, small = 1080;
			boolean fullHd = portrait ? vc.isSizeSupported(1080, 1920) : vc.isSizeSupported(1920, 1080);
			if (!fullHd)
			{
				big = 1280;
				small = 720;
			}
			width = portrait ? small : big;
			height = portrait ? big : small;
			MediaFormat f = MediaFormat.createVideoFormat(MIME, width, height);
			f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
					MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
			// Sharp text on mostly static frames: generous bitrate, sparse keyframes.
			f.setInteger(MediaFormat.KEY_BIT_RATE, fullHd ? 5_000_000 : 3_000_000);
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
			FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
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

		void finish()
		{
			codec.signalEndOfInputStream();
			drain(true);
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
