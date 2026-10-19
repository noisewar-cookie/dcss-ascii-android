package com.crawlmb.recording;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Game identity from NativeWrapper.getGameIdentity() (android_rec_game_identity_json). */
public final class GameIdentity
{
	public final String name;
	public final long birth;
	public final String seed;
	public final String dcssVersion;
	public final String species;
	public final String job;
	public final String gameType;
	public final boolean inGame;
	public final String json;

	private GameIdentity(String json, Map<String, Object> m)
	{
		this.json = json;
		name = MiniJson.getString(m, "name", "");
		birth = MiniJson.getLong(m, "birth", 0);
		seed = MiniJson.getString(m, "seed", "");
		dcssVersion = MiniJson.getString(m, "dcss_version", "");
		species = MiniJson.getString(m, "species", "");
		job = MiniJson.getString(m, "job", "");
		gameType = MiniJson.getString(m, "game_type", "");
		inGame = MiniJson.getLong(m, "in_game", 0) != 0;
	}

	public static GameIdentity parse(byte[] utf8)
	{
		String json = new String(utf8, StandardCharsets.UTF_8);
		return new GameIdentity(json, MiniJson.parseObject(json));
	}

	/** Stable per-game key: same character + same start time = same game. */
	public String key()
	{
		return name + "|" + birth;
	}

	/**
	 * Recording filename: character_seed_version, sanitized. Not unique (same
	 * name + seed replayed); Recordings.fileFor adds a numeric suffix on clash.
	 */
	public String fileName(int n)
	{
		String base = safeName() + "_" + seed.replaceAll("[^0-9]", "") + "_" + safeVersion();
		return (n > 1 ? base + "_" + n : base) + RecFormat.FILE_EXTENSION;
	}

	/** Clip filename without extension: character_from-to_version. */
	public String clipName(int fromTurn, int toTurn)
	{
		return safeName() + "_" + fromTurn + "-" + toTurn + "_" + safeVersion();
	}

	private String safeName()
	{
		return (name.isEmpty() ? "game" : name).replaceAll("[^A-Za-z0-9_-]", "_");
	}

	private String safeVersion()
	{
		return dcssVersion.replaceAll("[^A-Za-z0-9._-]", "_");
	}
}
