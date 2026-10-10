package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

/**
 * <p><b>Once-per-rest</b> resources (Second Wind, Turn Undead, Arcane Recovery): they get spent,
 * and a rest returns them.</p>
 *
 * <p>They used to live in a {@code Set<UUID>} per manager, indexed by <b>player</b>. That's wrong for two
 * distinct reasons, and both show up while playing:</p>
 *
 * <ul>
 *   <li><b>They belong to the character, not whoever's playing it.</b> With two characters, spending
 *       Second Wind on one also spent it for the other — the same failure family as shared level and HP.</li>
 *   <li><b>They didn't survive a restart.</b> The set lives in memory, so restarting the server gave
 *       everyone back their spent resources without having rested.</li>
 * </ul>
 *
 * <p>Storing it on the sheet makes both problems disappear at once, and it ends up alongside where
 * Smite-prepared and the Inspiration die already live, which were always fine.</p>
 */
final class RestResource {

	/** Fighter's Second Wind: once per short or long rest. */
	static final String SECOND_WIND = "secondWindUsed";
	/** Cleric's Channel Divinity: same, short or long. */
	static final String CHANNEL_DIVINITY = "channelDivinityUsed";
	/** Wizard's Arcane Recovery: only returned by a LONG rest. */
	static final String ARCANE_RECOVERY = "arcaneRecoveryUsed";

	/** Fighter's Action Surge: once per short or long rest. */
	static final String ACTION_SURGE = "actionSurgeUsed";
	/** Monk's Ki points spent: a count, all returned by a short or long rest. */
	static final String KI_USES = "kiSpent";
	/** Druid's Wild Shape: 2 uses, returned by a short or long rest. */
	static final String WILD_SHAPE_USES = "wildShapeUsesSpent";
	/** Sorcerer's sorcery points spent, returned by a long rest. */
	static final String SORCERY_POINTS = "sorceryPointsSpent";
	/** Barbarian Rage: a COUNT of uses spent, returned by a long rest. */
	static final String RAGE_USES = "rageUsesSpent";
	/** Bard's Bardic Inspiration: a count of uses spent; a long rest returns it (a short one too from level 5). */
	static final String INSPIRATION_USES = "inspirationUsesSpent";

	private RestResource() {
	}

	static int usesSpent(JsonObject sheet, String key) {
		if (sheet == null || !sheet.has(key)) return 0;
		try {
			return Math.max(0, sheet.get(key).getAsInt());
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/** Uses left out of {@code max}; {@link Integer#MAX_VALUE} means unlimited. */
	static int usesLeft(JsonObject sheet, String key, int max) {
		return max == Integer.MAX_VALUE ? max : Math.max(0, max - usesSpent(sheet, key));
	}

	/** Spends one use of a counted resource. Unlimited ones record nothing. */
	static void spendUse(ServerPlayer player, String key, int max) {
		spendUses(player, key, max, 1);
	}

	/** Spends {@code amount} uses at once (Twinned Spell costs as many sorcery points as the spell's level). */
	static void spendUses(ServerPlayer player, String key, int max, int amount) {
		if (max == Integer.MAX_VALUE) return;
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty(key, usesSpent(sheet, key) + amount);
		SheetLoader.saveServer(sheet, player.getStringUUID());
	}

	/** Returns every use of a counted resource. */
	static void restoreUses(ServerPlayer player, String key) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || !sheet.has(key)) return;
		sheet.remove(key);
		SheetLoader.saveServer(sheet, player.getStringUUID());
	}

	static boolean isSpent(JsonObject sheet, String key) {
		return sheet != null && sheet.has(key) && sheet.get(key).getAsBoolean();
	}

	/**
	 * <p>Spends it. Returns {@code false} if it was already spent, which combines the check and the spend
	 * into one call — same as {@code Set.add} used to, so the caller doesn't have to remember to do both.</p>
	 */
	static boolean spend(ServerPlayer player, String key) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || isSpent(sheet, key)) return false;
		sheet.addProperty(key, true);
		SheetLoader.saveServer(sheet, player.getStringUUID());
		return true;
	}

	/** Returns it. Does nothing if it wasn't spent, to avoid writing the sheet on every rest for everyone. */
	static void restore(ServerPlayer player, String key) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (!isSpent(sheet, key)) return;
		sheet.remove(key);
		SheetLoader.saveServer(sheet, player.getStringUUID());
	}
}
