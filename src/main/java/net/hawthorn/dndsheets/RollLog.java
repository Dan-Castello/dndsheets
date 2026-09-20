package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * <p>The game's most recent rolls, in memory: the full breakdown that {@link DiceManager.RollOutcome}
 * already computes (the same text seen in chat), but without it getting lost in the scroll. Hooked into
 * the single entry point every roll goes through, {@link DiceManager#roll} — no need to intercept each
 * of its callers separately.</p>
 *
 * <p><b>ponytail:</b> no roller name when the caller doesn't pass a sheet with {@code characterName} (a
 * monster, almost always, invoked with an empty {@code JsonObject}) — stored as {@code "?"} instead of
 * nothing. Passing the real identity through to {@code DiceManager.roll} would mean touching the dozens
 * of call sites that already use it; the roll's own breakdown (what's most missed without a log) is
 * already captured regardless. Upgrade this when someone actually misses it at a real table.</p>
 *
 * <p>Memory-only, same criterion as {@link TurnManager}: it's a SESSION history, not the character's,
 * so it doesn't need to survive a server restart.</p>
 */
public final class RollLog {
	private RollLog() {}

	public record Entry(String actor, String formatted, long timestampMillis) {}

	private static final int MAX_ENTRIES = 200;
	private static final Deque<Entry> entries = new ArrayDeque<>();

	public static synchronized void record(JsonObject sheet, DiceManager.RollOutcome outcome) {
		if (outcome.result() == null || outcome.formatted() == null) return; //Failed roll: nothing to log.
		String actor = sheet != null && sheet.has("characterName") ? sheet.get("characterName").getAsString() : "?";
		entries.addLast(new Entry(actor, outcome.formatted(), System.currentTimeMillis()));
		while (entries.size() > MAX_ENTRIES) entries.removeFirst();
	}

	/** The most recent rolls, most recent first. */
	public static synchronized List<Entry> recent() {
		List<Entry> copy = new ArrayList<>(entries);
		Collections.reverse(copy);
		return copy;
	}
}
