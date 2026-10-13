package com.crawlmb.recording;

/**
 * Replay screens are browse-only: recordings are baked data. Anything that
 * would change the game (wear, quaff, cast, toggle training, ...) shows a
 * toast instead. Pure Java so the key rules are unit-testable.
 */
public final class ReplayReadOnly
{
	public static final String MESSAGE = "Recordings can't be edited.";

	public enum Screen
	{
		INVENTORY, ITEM, SPELLS, SPELL, ABILITIES, ABILITY, CHARACTER, TRAINING, EXAMINE
	}

	private ReplayReadOnly() {}

	// DCSS item-describe actions (w)ield (W)ear (P)ut on (R)emove (T)ake off
	// (q)uaff (r)ead (e)at (E)voke (d)rop (f)ire (=)adjust ({)inscribe (u)se
	// (t)hrow (v)iew... v is not an edit.
	private static final String ITEM_EDIT_KEYS = "wWPRTqrEdf={ut";
	// Spell describe: (F)orget, (z)/(Z) cast, (M)emorise from list.
	private static final String SPELL_EDIT_KEYS = "FzZcM";
	// Ability describe / list: (a)ctivate.
	private static final String ABILITY_EDIT_KEYS = "a";

	/** True when `key` on `screen` would modify the game in a live session. */
	public static boolean isEditKey(Screen screen, int key)
	{
		switch (screen)
		{
		case ITEM:
			return ITEM_EDIT_KEYS.indexOf(key) >= 0;
		case SPELL:
		case SPELLS:
			return SPELL_EDIT_KEYS.indexOf(key) >= 0;
		case ABILITY:
			return ABILITY_EDIT_KEYS.indexOf(key) >= 0;
		case TRAINING:
			// In the skills menu, letters toggle training and '!'/'*' switch
			// training modes; all of that changes the game.
			return Character.isLetter(key) || key == '!' || key == '*';
		case INVENTORY:
		case ABILITIES:
		case CHARACTER:
		case EXAMINE:
		default:
			return false; // letters only select/browse here
		}
	}

	/** Browse screens and examine are only usable while paused. */
	public static boolean screensEnabled(boolean playing)
	{
		return !playing;
	}
}
