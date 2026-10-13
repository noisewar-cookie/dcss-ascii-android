package com.crawlmb.recording.ui;

import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * DCSS colour-tagged text -> coloured CharSequence. Same grammar and palette
 * as GameActivity.parseCrawlColourString (which can delegate here once this
 * lands): open-only "<colour>" tags, "<<" is a literal '<', "<bg:...>" ignored.
 * Also strips the "</colour>" closing tags DCSS formatted_strings sometimes emit.
 */
public final class CrawlText
{
	public static final int LIGHTGRAY = 0xFFC0C0C0;
	private static final Map<String, Integer> COLOURS = new HashMap<>();
	static
	{
		COLOURS.put("black", 0xFF000000);
		COLOURS.put("blue", 0xFF0040FF);
		COLOURS.put("green", 0xFF008040);
		COLOURS.put("cyan", 0xFF00A0A0);
		COLOURS.put("red", 0xFFFF4040);
		COLOURS.put("magenta", 0xFF9020FF);
		COLOURS.put("brown", 0xFFA64800);
		COLOURS.put("lightgrey", LIGHTGRAY);
		COLOURS.put("lightgray", LIGHTGRAY);
		COLOURS.put("darkgrey", 0xFF606060);
		COLOURS.put("darkgray", 0xFF606060);
		COLOURS.put("lightblue", 0xFF00FFFF);
		COLOURS.put("lightgreen", 0xFF00FF00);
		COLOURS.put("lightcyan", 0xFF20FFDC);
		COLOURS.put("lightred", 0xFFFF5050);
		COLOURS.put("lightmagenta", 0xFFFA4FFD);
		COLOURS.put("yellow", 0xFFFFFF00);
		COLOURS.put("white", 0xFFFFFFFF);
		// formatted_string::get_colour shorthands.
		COLOURS.put("h", 0xFFFFFF00);
		COLOURS.put("w", 0xFFFFFFFF);
	}

	private CrawlText() {}

	/** ARGB for a lowercase colour tag name, or null if it isn't one. */
	public static Integer colour(String tag)
	{
		return COLOURS.get(tag);
	}

	public static CharSequence parse(String s)
	{
		SpannableStringBuilder out = new SpannableStringBuilder();
		if (s == null)
			return out;
		int cur = LIGHTGRAY;
		int i = 0, n = s.length();
		while (i < n)
		{
			char ch = s.charAt(i);
			if (ch == '<' && i + 1 < n && s.charAt(i + 1) == '<')
			{
				append(out, "<", cur);
				i += 2;
				continue;
			}
			if (ch == '<')
			{
				int close = s.indexOf('>', i + 1);
				if (close < 0)
				{
					append(out, s.substring(i), cur);
					break;
				}
				String tag = s.substring(i + 1, close).toLowerCase(Locale.ROOT);
				i = close + 1;
				if (tag.startsWith("/"))
				{
					cur = LIGHTGRAY;
					continue;
				}
				if (tag.startsWith("bg:"))
					continue;
				Integer c = COLOURS.get(tag);
				if (c != null)
					cur = c;
				continue;
			}
			int next = s.indexOf('<', i);
			if (next < 0)
				next = n;
			append(out, s.substring(i, next), cur);
			i = next;
		}
		return out;
	}

	private static void append(SpannableStringBuilder out, String text, int colour)
	{
		if (text.isEmpty())
			return;
		int start = out.length();
		out.append(text);
		out.setSpan(new ForegroundColorSpan(colour), start, out.length(),
				Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
	}
}
