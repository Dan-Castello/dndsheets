package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * <p>Single formatting point for everything the mod announces in chat (rolls, attacks, spells, death
 * saves). Previously every call site built its own plain-text line; now it all goes through here, with a
 * colored category tag, names in gold, hits in green, misses in gray, damage in red, and death events in
 * dark red — so what's happening can be followed at a glance in a busy chat, instead of having to read
 * every full line.</p>
 */
public class ChatFeedback {
	private static final ChatFormatting NAME = ChatFormatting.GOLD;
	private static final ChatFormatting HIT = ChatFormatting.GREEN;
	private static final ChatFormatting MISS = ChatFormatting.GRAY;
	private static final ChatFormatting DAMAGE = ChatFormatting.RED;
	private static final ChatFormatting ROLL = ChatFormatting.AQUA;
	private static final ChatFormatting DANGER = ChatFormatting.DARK_RED;
	private static final ChatFormatting GOOD = ChatFormatting.GREEN;
	private static final ChatFormatting COMBAT_TAG = ChatFormatting.DARK_AQUA;
	private static final ChatFormatting MAGIC_TAG = ChatFormatting.LIGHT_PURPLE;
	private static final ChatFormatting ROLL_TAG = ChatFormatting.BLUE;

	//Single color for activating a class resource (Rage, Second Wind, Bardic Inspiration...). Previously
	//each manager picked its own loose ChatFormatting — Rage in RED, the same color that already means
	//"damage taken" everywhere else in the mod — with no relation to any real palette. Package-private on
	//purpose: only used by the class-resource managers, all in this same package.
	static final ChatFormatting RESOURCE = ChatFormatting.YELLOW;

	//Used to be server-wide (broadcastSystemMessage): with a large table, every roll/attack/spell from
	//ONE fight was seen by the whole server, drowning out the chat of anyone not involved in that fight
	//(not to mention two groups playing separate scenes at once). Scoped to whoever's actually nearby —
	//same radius the turn-order mode already uses to decide who participates (TurnManager.DEFAULT_RADIUS),
	//consistent with "who could plausibly be part of this encounter".
	public static void broadcast(Entity source, Component message) {
		Level level = source.level();
		if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) return;
		double radiusSq = TurnManager.DEFAULT_RADIUS * TurnManager.DEFAULT_RADIUS;
		for (ServerPlayer player : serverLevel.players()) {
			if (player.distanceToSqr(source) <= radiusSq) player.sendSystemMessage(message);
		}
	}

	private static MutableComponent tag(String labelKey, ChatFormatting color) {
		MutableComponent result = Component.literal("[").withStyle(color, ChatFormatting.BOLD);
		result.append(Component.translatable(labelKey));
		result.append(Component.literal("] "));
		return result;
	}

	//Character/monster/weapon/spell names come from content JSON or the sheet, not from fixed code — a
	//"§" in a name is Minecraft's formatting code, so it would bleed color/bold into the rest of the chat
	//line for ALL players even inside a Component.literal. name() and dim() are the two points that all
	//interpolated text in this whole file passes through.
	private static String stripFormatting(String text) {
		return text == null ? null : text.replace('§', '?');
	}

	//ContentNames and not literal: both a CHARACTER's name (free text from the sheet) and a MONSTER's
	//(comes from the content pack and can be a language key) pass through here. The former doesn't look
	//like any key and renders the same as before; the latter resolves in the reader's language.
	//Distinguishing them here would mean threading where each one comes from through half a dozen signatures.
	private static MutableComponent name(String text) {
		return ContentNames.of(stripFormatting(text)).withStyle(NAME, ChatFormatting.BOLD);
	}

	private static MutableComponent dim(String text) {
		return Component.literal(stripFormatting(text)).withStyle(ChatFormatting.GRAY);
	}

	//Overloads for text that's already translated (Component.translatable) instead of hand-interpolated fixed text.
	private static MutableComponent name(Component text) {
		return text.copy().withStyle(NAME, ChatFormatting.BOLD);
	}

	private static MutableComponent dim(Component text) {
		return text.copy().withStyle(ChatFormatting.GRAY);
	}

	/** Marker for the summary inside an insertion. Public: {@code client.CombatLogOverlay} extracts it. */
	public static final String SUMMARY_PREFIX = "dndlog:";

	//ONE-line summary for the on-screen panel (CombatLogOverlay), hung INSIDE the Component itself as
	//the "insertion" of an empty chunk: chat doesn't render it, the network serializes it for free along
	//with the style, and the overlay extracts it — a single source of truth (this file) and zero new
	//messages. Data only (names, numbers, symbols), never prose: the words live in the full line's
	//translatable keys, and a summary with fixed words would repeat bug #15.
	private static MutableComponent withSummary(MutableComponent full, String summary) {
		return full.append(Component.literal("").withStyle(style -> style.withInsertion(SUMMARY_PREFIX + summary)));
	}

	//"15 = 13[1d20] + 2" → "15": the total is the only thing the summary needs from the breakdown.
	private static String totalOf(String rollText) {
		if (rollText == null) return "";
		int equals = rollText.indexOf('=');
		return (equals > 0 ? rollText.substring(0, equals) : rollText).trim();
	}

	//"20 = 18[1d20] + 2 (Initiative)" → "Initiative": some callers append the context to the end of the
	//expression itself instead of passing it separately, and without recovering it the summary was left
	//as just "Wizard · 22" — the number with no idea what it was for.
	private static String contextOf(String rollText) {
		if (rollText == null) return "";
		int open = rollText.lastIndexOf('(');
		int close = rollText.lastIndexOf(')');
		return open >= 0 && close > open ? rollText.substring(open + 1, close).trim() : "";
	}

	//[Roll] So-and-so rolls Strength: 15=15[1d20]+2
	public static MutableComponent roll(String characterName, String context, String rollText) {
		MutableComponent msg = tag("chat.dndsheets.tag.roll", ROLL_TAG).append(name(characterName));
		msg.append(dim(context != null && !context.isBlank()
			? Component.translatable("chat.dndsheets.roll.with_context", context)
			: Component.translatable("chat.dndsheets.roll.no_context")));
		msg.append(Component.literal(rollText).withStyle(ROLL, ChatFormatting.BOLD));
		String summaryContext = context != null && !context.isBlank() ? context : contextOf(rollText);
		return withSummary(msg, characterName + " · " + totalOf(rollText)
			+ (summaryContext.isBlank() ? "" : " · " + summaryContext));
	}

	//[Roll] So-and-so rolls: 15=15[1d20]+2 (Strength) and 7=7[1d6]+2 (Damage) — buttons with several rolls at once.
	public static MutableComponent multiRoll(String characterName, java.util.List<String> contexts, java.util.List<String> rollTexts) {
		MutableComponent msg = tag("chat.dndsheets.tag.roll", ROLL_TAG).append(name(characterName))
			.append(dim(Component.translatable("chat.dndsheets.roll.multi_intro")));
		StringBuilder totals = new StringBuilder();
		for (int i = 0; i < rollTexts.size(); i++) {
			if (i > 0) msg.append(dim(Component.translatable("chat.dndsheets.roll.and")));
			msg.append(Component.literal(rollTexts.get(i)).withStyle(ROLL, ChatFormatting.BOLD));
			String context = i < contexts.size() ? contexts.get(i) : null;
			if (context != null && !context.isBlank()) msg.append(dim(Component.translatable("chat.dndsheets.roll.context_paren", context)));
			if (totals.length() > 0) totals.append(" / ");
			totals.append(totalOf(rollTexts.get(i)));
			//The context in the summary too — most simple rolls go through HERE (a one-element list),
			//and without this the panel showed "Wizard · 22" without saying 22 of WHAT.
			String pieceContext = context != null && !context.isBlank() ? context : contextOf(rollTexts.get(i));
			if (!pieceContext.isBlank()) totals.append(rollTexts.size() == 1 ? " · " : " ").append(pieceContext);
		}
		return withSummary(msg, characterName + " · " + totals);
	}

	//[Roll] The roll didn't work: <reason>
	public static MutableComponent rollFailed(String reason) {
		return tag("chat.dndsheets.tag.roll", ChatFormatting.RED).append(Component.literal(reason).withStyle(ChatFormatting.RED));
	}

	//[Combat] So-and-so hits with Sword: 7=7[1d6]+4  (training dummy, no AC involved)
	public static MutableComponent damageOnly(String characterName, String weaponName, String rollText) {
		return withSummary(tag("chat.dndsheets.tag.combat", COMBAT_TAG)
			.append(name(characterName))
			.append(dim(Component.translatable("chat.dndsheets.combat.hits_with", ContentNames.of(weaponName))))
			.append(Component.literal(rollText).withStyle(DAMAGE, ChatFormatting.BOLD)),
			characterName + " · " + ContentNames.plain(weaponName) + " · " + totalOf(rollText));
	}

	//[Combat] So-and-so attacks Whoever with Sword: 15 vs AC 13 → Hit! Damage: 7=7[1d6]+4
	public static MutableComponent attackResult(String attackerName, String targetName, String weaponName, String rollText, int ac, boolean hit, String damageText) {
		MutableComponent msg = tag("chat.dndsheets.tag.combat", COMBAT_TAG)
			.append(name(attackerName))
			.append(dim(Component.translatable("chat.dndsheets.combat.attacks")))
			.append(name(targetName))
			.append(dim(Component.translatable("chat.dndsheets.combat.with_weapon_vs_ac", ContentNames.of(weaponName), rollText, ac)));
		if (hit) {
			msg.append(Component.translatable("chat.dndsheets.combat.hit").withStyle(HIT, ChatFormatting.BOLD));
			msg.append(dim(Component.translatable("chat.dndsheets.combat.damage_label")));
			msg.append(Component.literal(damageText).withStyle(DAMAGE, ChatFormatting.BOLD));
		} else {
			msg.append(Component.translatable("chat.dndsheets.combat.miss").withStyle(MISS, ChatFormatting.ITALIC));
		}
		//"✓ 6" = hit for 6 damage; "—" = missed. Only symbols already used elsewhere in the mod.
		return withSummary(msg, ContentNames.plain(attackerName) + " ▶ " + ContentNames.plain(targetName) + " · " + totalOf(rollText)
			+ (hit ? " ✓ " + totalOf(damageText) : " —"));
	}

	//Same as above, plus a Bardic Inspiration note appended to the SAME line (0 = no inspiration, adds
	//nothing). Previously every attack with active inspiration was TWO separate broadcast() calls
	//("So-and-so uses their Bardic Inspiration" + the attack line itself) — same event, same turn, twice
	//the lines in a chat that already fills up fast with several players and enemies acting per round.
	public static MutableComponent attackResult(String attackerName, String targetName, String weaponName, String rollText, int ac, boolean hit, String damageText, int inspiration) {
		MutableComponent msg = attackResult(attackerName, targetName, weaponName, rollText, ac, hit, damageText);
		if (inspiration > 0) msg.append(dim(Component.translatable("chat.dndsheets.combat.inspiration_note", inspiration)));
		return msg;
	}

	/**
	 * <p>Legendary Resistance note on the same line as the save. Without saying so, a boss that fails the
	 * roll and takes no damage reads as a mod bug, not as the resource it just spent — and the group needs
	 * to know it has one fewer left, which is exactly the information that makes the rule interesting.</p>
	 */
	public static MutableComponent withLegendaryResistance(MutableComponent msg, boolean used, int left) {
		if (!used) return msg;
		return msg.append(Component.translatable("chat.dndsheets.monster.legendary_resistance", left)
			.withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE, net.minecraft.ChatFormatting.BOLD));
	}

	/**
	 * <p>Appends a cover note to the SAME line as the attack, the same way the Inspiration note does.
	 * Without it, a miss against an AC higher than the monster's sheet says reads as a mod error and not
	 * as the cover it actually is.</p>
	 */
	public static MutableComponent withCover(MutableComponent msg, Cover cover) {
		if (cover == Cover.NONE) return msg;
		return msg.append(dim(Component.translatable("chat.dndsheets.combat.cover_note", Component.translatable(cover.langKey()), cover.bonus())));
	}

	//[Magic] So-and-so heals Whoever with Cure Wounds: 8=8[1d8]+3 HP
	public static MutableComponent healResult(String casterName, String targetName, String spellName, String healText) {
		return withSummary(tag("chat.dndsheets.tag.magic", MAGIC_TAG)
			.append(name(casterName))
			.append(dim(Component.translatable("chat.dndsheets.magic.heals")))
			.append(name(targetName))
			.append(dim(Component.translatable("chat.dndsheets.magic.with_spell", ContentNames.of(spellName))))
			.append(Component.translatable("chat.dndsheets.magic.heal_amount", healText).withStyle(GOOD, ChatFormatting.BOLD)),
			casterName + " ▶ " + ContentNames.plain(targetName) + " · +" + totalOf(healText));
	}

	//[Magic] So-and-so casts Fireball at Whoever: save 12 vs DC 15 → Fails the save. Damage: 24
	public static MutableComponent saveResult(String casterName, String targetName, String spellName, String saveRollText, int dc, boolean saved, Component outcomeLabel, String damageText) {
		MutableComponent msg = tag("chat.dndsheets.tag.magic", MAGIC_TAG)
			.append(name(casterName))
			.append(dim(Component.translatable("chat.dndsheets.magic.casts_against", ContentNames.of(spellName))))
			.append(name(targetName))
			.append(dim(Component.translatable("chat.dndsheets.magic.save_vs_dc", saveRollText, dc)));
		msg.append(outcomeLabel.copy().withStyle(saved ? HIT : MISS, saved ? ChatFormatting.BOLD : ChatFormatting.ITALIC));
		if (damageText != null) {
			msg.append(dim(Component.translatable("chat.dndsheets.magic.damage_label")));
			msg.append(Component.literal(damageText).withStyle(DAMAGE, ChatFormatting.BOLD));
		}
		//"12/DC 15 ✓" = saved; "— 24" = failed and took 24. Same symbols as the attack summary.
		return withSummary(msg, ContentNames.plain(spellName) + " ▶ " + ContentNames.plain(targetName) + " · " + totalOf(saveRollText) + "/DC " + dc
			+ (saved ? " ✓" : " —") + (damageText != null ? " " + totalOf(damageText) : ""));
	}

	//[Death] So-and-so has dropped to 0 HP and needs death saves!
	public static MutableComponent downed(String characterName) {
		return tag("chat.dndsheets.tag.death", DANGER)
			.append(name(characterName))
			.append(Component.translatable("chat.dndsheets.death.downed").withStyle(DANGER));
	}

	//[Death] So-and-so rolls a death save: 15 → Successes ●●○ Failures ○○○
	public static MutableComponent deathSaveRoll(String characterName, int rollValue, int successes, int failures) {
		return withSummary(tag("chat.dndsheets.tag.death", DANGER)
			.append(name(characterName))
			.append(dim(Component.translatable("chat.dndsheets.death.save_roll")))
			.append(Component.literal(String.valueOf(rollValue)).withStyle(ROLL, ChatFormatting.BOLD))
			.append(dim(Component.translatable("chat.dndsheets.death.successes")))
			.append(Component.literal(marks(successes)).withStyle(GOOD))
			.append(dim(Component.translatable("chat.dndsheets.death.failures")))
			.append(Component.literal(marks(failures)).withStyle(DAMAGE)),
			characterName + " · " + rollValue + " · " + marks(successes) + "/" + marks(failures));
	}

	private static String marks(int count) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < 3; i++) builder.append(i < count ? "●" : "○");
		return builder.toString();
	}

	//[Death] So-and-so rolls a natural 20 on their death save: they come to!
	public static MutableComponent naturalTwenty(String characterName) {
		return tag("chat.dndsheets.tag.death", GOOD)
			.append(name(characterName))
			.append(Component.translatable("chat.dndsheets.death.natural_twenty").withStyle(GOOD, ChatFormatting.BOLD));
	}

	//[Death] So-and-so revives Whoever.
	public static MutableComponent revived(String reviverName, String targetName) {
		return tag("chat.dndsheets.tag.death", GOOD)
			.append(name(reviverName))
			.append(dim(Component.translatable("chat.dndsheets.death.revives")))
			.append(name(targetName))
			.append(dim("."));
	}

	//[Death] So-and-so stops fighting and dies.
	public static MutableComponent givesUp(String characterName) {
		return tag("chat.dndsheets.tag.death", DANGER)
			.append(name(characterName))
			.append(Component.translatable("chat.dndsheets.death.gives_up").withStyle(DANGER));
	}
}
