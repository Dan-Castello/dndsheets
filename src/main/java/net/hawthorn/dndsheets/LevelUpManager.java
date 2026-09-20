package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * <p>Leveling up: what changes on its own, what has to be chosen, and announcing it.</p>
 *
 * <p>Almost everything a level grants in 5e was already <b>derived</b> from the {@code characterLevel}
 * field — max hit points, proficiency bonus, spell slots, Sneak Attack and Martial Arts dice. What was
 * missing was the one thing that can't be derived because it's a <b>decision</b>: the Ability Score
 * Improvement at levels 4, 8, 12, 16, and 19. Without it, a level-20 character fought with the ability
 * scores of a level-1 one, which is the most noticeable number of them all.</p>
 *
 * <p>And it needed to be <b>announced</b>. {@code /dndsheet setlevel 5} silently changed half the sheet:
 * max HP, proficiency, and slots moved without a single line in chat. Leveling up is one of the few
 * moments in a campaign the table celebrates, and it was the least visible one.</p>
 *
 * <p>Pending improvements are recorded <b>on the sheet</b> ({@code pendingAbilityImprovements}) rather
 * than in memory: that way they survive closing the screen, disconnecting, and a server restart, and
 * jumping straight from level 1 to level 8 grants the two that are owed instead of losing one. It's
 * also what makes the client screen safe: the server only applies an improvement if one was truly
 * still pending.</p>
 */
public class LevelUpManager {

	public static final int MAX_LEVEL = 20;
	/** 5e cap for an ability score raised through improvements. */
	public static final int MAX_ABILITY = 20;
	private static final String PENDING = "pendingAbilityImprovements";

	private static final String[] ABILITIES = {"strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"};

	/** The levels that grant an Ability Score Improvement in 5e. */
	static boolean isImprovementLevel(int level) {
		return level == 4 || level == 8 || level == 12 || level == 16 || level == 19;
	}

	/** How many improvements are gained going from {@code from} to {@code to}. Leveling down removes none. */
	static int improvementsBetween(int from, int to) {
		int count = 0;
		for (int level = from + 1; level <= to; level++) {
			if (isImprovementLevel(level)) count++;
		}
		return count;
	}

	public static int pendingOf(JsonObject sheet) {
		return sheet != null && sheet.has(PENDING) ? Math.max(0, sheet.get(PENDING).getAsInt()) : 0;
	}

	/**
	 * <p>Records the improvements the level jump grants. Called from the ONE place that changes level
	 * ({@code SheetCommand.applyLevel}), so it doesn't matter whether the change came from the command or
	 * the DM Panel.</p>
	 */
	public static void grantImprovementsFor(JsonObject sheet, int fromLevel, int toLevel) {
		int granted = improvementsBetween(fromLevel, toLevel);
		if (granted > 0) sheet.addProperty(PENDING, pendingOf(sheet) + granted);
	}

	/**
	 * <p>Levels up and reports what changed. Triggered by the DM: at a table, whoever hands out levels is
	 * whoever runs the game — leaving it to the player would turn leveling into a button.</p>
	 */
	public static void levelUp(ServerPlayer target) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;

		//characterLevelOf(sheet) —the explicit one— and NOT the overload with the player, which falls back
		//to Minecraft's XP level when the sheet has no level set. That fallback is fine for DISPLAYING a
		//number while the DM hasn't set one, and it's poison for deciding the next one: a player who had
		//mined stone up to XP level 25 could never level up ("you're already at 20"), and one with XP
		//level 7 would suddenly jump to 8 the first time, Ability Score Improvement included, just from
		//mining. A character nobody has leveled up is level 1, no matter how much they've mined.
		int before = Math.max(1, SheetLoader.characterLevelOf(sheet));
		if (before >= MAX_LEVEL) {
			target.sendSystemMessage(Component.translatable("chat.dndsheets.levelup.at_max", MAX_LEVEL).withStyle(ChatFormatting.GRAY));
			return;
		}

		int hpBefore = intOf(sheet, "maxHp");
		int profBefore = intOf(sheet, "proficiencyBonus");
		int slotsBefore = intOf(sheet, "spellSlotsMax");

		//A single path for changing level: the same one the command and the DM Panel use, which already
		//re-derives max HP, proficiency, and slots. Duplicating that derivation here would be the third copy.
		net.hawthorn.dndsheets.command.SheetCommand.applyLevel(target, before + 1);

		JsonObject updated = SheetLoader.getServerSheet(target.getStringUUID());
		String name = SheetLoader.characterNameOf(updated, target);
		ChatFeedback.broadcast(target, Component.translatable("chat.dndsheets.levelup.announce", name, before + 1).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		reportGain(target, "chat.dndsheets.levelup.hp", hpBefore, intOf(updated, "maxHp"));
		reportGain(target, "chat.dndsheets.levelup.proficiency", profBefore, intOf(updated, "proficiencyBonus"));
		reportGain(target, "chat.dndsheets.levelup.slots", slotsBefore, intOf(updated, "spellSlotsMax"));

		if (pendingOf(updated) > 0) openImprovementScreen(target, pendingOf(updated));
	}

	//Only what actually went up gets announced: a list where three out of four lines say "unchanged"
	//makes the one that did change go unnoticed.
	private static void reportGain(ServerPlayer target, String key, int before, int after) {
		if (after > before) {
			target.sendSystemMessage(Component.translatable(key, before, after).withStyle(ChatFormatting.GREEN));
		}
	}

	public static void openImprovementScreen(ServerPlayer target, int pending) {
		target.sendSystemMessage(Component.translatable("chat.dndsheets.levelup.improvement_pending", pending).withStyle(ChatFormatting.GOLD));
		DndsheetsMod.PACKET_HANDLER.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> target),
			new net.hawthorn.dndsheets.network.ScreenActionMessage(net.hawthorn.dndsheets.network.ScreenActionMessage.Action.ABILITY_IMPROVEMENT_OPEN));
	}

	/**
	 * <p>Applies an improvement: +2 to one ability score, or +1 to two different ones. Returns false if
	 * none was pending or if the choice isn't valid.</p>
	 *
	 * <p>Validated <b>on the server</b>, not in the screen: a client can send whatever message it wants,
	 * and "only if you actually earned it" is exactly the kind of check that can't live where the player
	 * is in charge.</p>
	 */
	public static boolean applyImprovement(ServerPlayer target, String firstAbility, String secondAbility) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null || pendingOf(sheet) <= 0) return false;

		String first = normalize(firstAbility);
		String second = normalize(secondAbility);
		if (first == null) return false;
		//+2 to one, or +1 to TWO DIFFERENT ones: repeating the same ability score twice would be a
		//disguised +2 that also skips the one-at-a-time cap.
		if (second != null && second.equals(first)) return false;

		if (second == null) {
			if (!raise(sheet, first, 2)) return false;
		} else {
			//Both or neither: applying one and failing the other would leave half an improvement spent.
			if (scoreOf(sheet, first) >= MAX_ABILITY || scoreOf(sheet, second) >= MAX_ABILITY) return false;
			raise(sheet, first, 1);
			raise(sheet, second, 1);
		}

		sheet.addProperty(PENDING, pendingOf(sheet) - 1);
		//Constitution changes max HP, so it has to be re-derived: without this, raising CON gave the new
		//modifier to everything except the one thing Constitution is meant to affect.
		SheetLoader.applyClassHitPoints(target, sheet);
		SheetLoader.saveServer(sheet, target.getStringUUID());
		DndsheetsMod.PACKET_HANDLER.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> target),
			new net.hawthorn.dndsheets.network.SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

		String name = SheetLoader.characterNameOf(sheet, target);
		ChatFeedback.broadcast(target, Component.translatable("chat.dndsheets.levelup.improved", name,
			second == null ? label(first).append(" +2") : label(first).append(" +1, ").append(label(second)).append(" +1")).withStyle(ChatFormatting.GREEN));
		if (pendingOf(sheet) > 0) openImprovementScreen(target, pendingOf(sheet));
		return true;
	}

	/**
	 * <p>Taking a feat <b>instead of</b> the ability score improvement. Spends the same pending slot
	 * through the same path: if they were two separate resources, a level 4 would grant both and the
	 * choice would stop being a choice.</p>
	 *
	 * <p>Validated on the server, same as the improvement and for the same reason: "only if you earned
	 * it" and "only once" are checks that can't live where the player is in charge.</p>
	 */
	public static boolean applyFeat(ServerPlayer target, String featId) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null || pendingOf(sheet) <= 0) return false;
		if (!FeatRegistry.grant(sheet, featId, MAX_ABILITY, SheetLoader.characterLevelOf(sheet))) return false;

		sheet.addProperty(PENDING, pendingOf(sheet) - 1);
		//Same as the improvement: a feat that raises Constitution has to re-derive max HP.
		SheetLoader.applyClassHitPoints(target, sheet);
		SheetLoader.saveServer(sheet, target.getStringUUID());
		DndsheetsMod.PACKET_HANDLER.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> target),
			new net.hawthorn.dndsheets.network.SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

		String name = SheetLoader.characterNameOf(sheet, target);
		ChatFeedback.broadcast(target, Component.translatable("chat.dndsheets.levelup.feat_taken", name,
			FeatRegistry.get(featId).name()).withStyle(ChatFormatting.GREEN));
		if (pendingOf(sheet) > 0) openImprovementScreen(target, pendingOf(sheet));
		return true;
	}

	private static boolean raise(JsonObject sheet, String ability, int amount) {
		int score = scoreOf(sheet, ability);
		if (score >= MAX_ABILITY) return false;
		sheet.addProperty(ability, String.valueOf(Math.min(MAX_ABILITY, score + amount)));
		return true;
	}

	private static int scoreOf(JsonObject sheet, String ability) {
		if (!sheet.has(ability)) return 10;
		try {
			return Integer.parseInt(sheet.get(ability).getAsString());
		} catch (RuntimeException e) {
			//An old sheet may have anything in there, even an object: 10 is the 5e default score and the
			//same thing the rest of the mod assumes.
			return 10;
		}
	}

	//Only the six are accepted: anything else that comes in over the wire isn't an ability score.
	@Nullable
	private static String normalize(String raw) {
		if (raw == null || raw.isEmpty()) return null;
		String lower = raw.toLowerCase(Locale.ROOT);
		for (String ability : ABILITIES) {
			if (ability.equals(lower)) return ability;
		}
		return null;
	}

	private static net.minecraft.network.chat.MutableComponent label(String ability) {
		return Component.translatable(switch (ability) {
			case "strength" -> "gui.dndsheets.character_sheet.ability_str";
			case "dexterity" -> "gui.dndsheets.character_sheet.ability_dex";
			case "constitution" -> "gui.dndsheets.character_sheet.ability_con";
			case "intelligence" -> "gui.dndsheets.character_sheet.ability_int";
			case "wisdom" -> "gui.dndsheets.character_sheet.ability_wis";
			default -> "gui.dndsheets.character_sheet.ability_cha";
		});
	}

	private static int intOf(JsonObject sheet, String key) {
		if (sheet == null || !sheet.has(key)) return 0;
		try {
			return Integer.parseInt(sheet.get(key).getAsString());
		} catch (RuntimeException e) {
			return 0;
		}
	}
}
