package com.crawlmb.recording;

/**
 * Shared constants for the .dcrec recording format.
 * See RecordingImplementation.md and android-recorder.cc.
 *
 * Pure Java (no Android imports) so the codec is unit-testable on the JVM.
 */
public final class RecFormat
{
	private RecFormat() {}

	/**
	 * Format version this build writes and the newest it can read.
	 * v2: snapshot docs zlib-chained to the previous doc (Deflate FDICT).
	 * v3: blobs may chain to an earlier blob (blobs.base); sessions.version.
	 * Bump on any change an older reader would misread; additive doc/meta
	 * keys and columns that old readers can ignore don't need a bump.
	 */
	public static final int SCHEMA_VERSION = 3;
	/** Oldest reader that can play what this build writes (meta min_reader_version). */
	public static final int MIN_READER_VERSION = 3;
	/** Screen-track chunk header byte; versioned apart from the container. */
	public static final int CHUNK_VERSION = 2;
	public static final String FILE_EXTENSION = ".dcrec";

	// ---- Screen-track chunk event types (FrameChunkEncoder/Decoder) ----
	public static final int CHUNK_MAGIC = 0x44434631; // "DCF1"
	public static final int EV_KEYFRAME = 0x01;
	public static final int EV_DELTA = 0x02;
	public static final int EV_PALETTE_ADD = 0x04;
	public static final int EV_HUD_TITLE = 0x10;
	public static final int EV_HUD_VITALS = 0x11;
	public static final int EV_HUD_BARS = 0x12;
	public static final int EV_STATUS_LIGHTS = 0x13;
	public static final int EV_MSG_HIST_MODE = 0x20;
	public static final int EV_MSG_HIST_CONTENT = 0x21;
	public static final int EV_CHARLOG_MODE = 0x22;
	public static final int EV_MAP_ANCHOR = 0x23;
	public static final int EV_LAYOUT = 0x40;
	// Unknown types >= EV_SKIPPABLE carry a varint byte length and can be skipped.
	public static final int EV_SKIPPABLE = 0x80;

	// ---- Frame flags (frames.flags) ----
	public static final int FRAME_INPUT_WAIT = 1;   // game waited for a key on this frame
	public static final int FRAME_KEY_IN_CHUNK = 2; // frame is its chunk's keyframe
	public static final int FRAME_POST_GAME = 4;    // after GAME_END (morgue/hiscore tail)

	// ---- Snapshot payload from native (android-recorder.cc) ----
	public static final int SNAPSHOT_MAGIC = 0x44435331; // "DCS1"

	// ---- Blob kinds (must match android-recorder.cc blob_kind) ----
	public static final int BLOB_PANEL = 1;
	public static final int BLOB_ITEM_DESC = 2;
	public static final int BLOB_MONS_DESC = 3;
	public static final int BLOB_FEAT_DESC = 4;
	public static final int BLOB_FEAT_NAME = 5;
	public static final int BLOB_SPELL_DESC = 6;
	public static final int BLOB_ABIL_DESC = 7;

	// ---- Snapshot kinds ----
	public static final String KIND_COMMAND_PROMPT = "CP";
	public static final String KIND_TURN_END = "TE";
	public static final String KIND_GAME_END = "END";

	// ---- Session kinds ----
	public static final String SESSION_NEW = "new";
	public static final String SESSION_RESUME = "resume";

	// ---- Layout array indices (ScreenState.layout) ----
	public static final int LAYOUT_MSG_WRAP_COLS = 0;
	public static final int LAYOUT_MSG_ROWS = 1;
	public static final int LAYOUT_PROSE_COLS = 2;
	public static final int LAYOUT_SKILLS_COLS = 3;
	public static final int LAYOUT_COMPACT_HUD = 4;
	public static final int LAYOUT_NEWTURN_MARK = 5;
	public static final int LAYOUT_MP_BAR_ARGB = 6;
	public static final int LAYOUT_SIZE = 7;

	// ---- Chunking thresholds ----
	public static final int CHUNK_MAX_FRAMES = 256;
	public static final int CHUNK_MAX_RAW_BYTES = 512 * 1024;
}
