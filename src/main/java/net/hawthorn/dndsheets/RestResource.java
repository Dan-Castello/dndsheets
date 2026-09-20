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

	private RestResource() {
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
