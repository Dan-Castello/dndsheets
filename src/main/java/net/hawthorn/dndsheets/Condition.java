package net.hawthorn.dndsheets;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * <p>The 14 conditions from 5e. Until now the mod could only express a "freely-named effect that deals
 * X damage dice over N turns" ({@link TurnManager.StatusEffect}) — a bleed timer, not a condition:
 * nothing in the engine read that name to change a roll, so "prone" or "paralyzed" were indistinguishable
 * from "fire" except by the chat text.</p>
 *
 * <p>Each rule is written as a {@code switch} in the method that applies it, not as a constructor with
 * seven positional booleans: that way each line below reads like the rulebook sentence it encodes, and
 * adding a new rule doesn't force touching all 14 constants.</p>
 *
 * <p>What is deliberately NOT modeled here: "charmed" (can't attack whoever charmed it) and "frightened"
 * (only while it can see the source) depend on <em>who</em> the source is, not just on having the
 * condition. They're registered and displayed, and frightened applies its attack disadvantage without
 * checking line of sight — the conservative approximation, and one a DM can correct by hand.</p>
 */
public enum Condition {
	BLINDED, CHARMED, DEAFENED, FRIGHTENED, GRAPPLED, INCAPACITATED, INVISIBLE,
	PARALYZED, PETRIFIED, POISONED, PRONE, RESTRAINED, STUNNED, UNCONSCIOUS;

	/** This combatant attacks with disadvantage. */
	public boolean selfAttackDisadvantage() {
		return switch (this) {
			case BLINDED, FRIGHTENED, POISONED, PRONE, RESTRAINED -> true;
			default -> false;
		};
	}

	/** This combatant attacks with advantage. */
	public boolean selfAttackAdvantage() {
		return this == INVISIBLE;
	}

	/**
	 * Attackers get advantage against it. Prone is deliberately excluded: it only grants advantage in
	 * melee and gives <em>disadvantage</em> at range, so {@link Combatant#advantageAgainst} resolves it
	 * with the actual distance in hand.
	 */
	public boolean attackersAdvantage() {
		return switch (this) {
			case BLINDED, PARALYZED, PETRIFIED, RESTRAINED, STUNNED, UNCONSCIOUS -> true;
			default -> false;
		};
	}

	/** Attackers get disadvantage against it. */
	public boolean attackersDisadvantage() {
		return this == INVISIBLE;
	}

	/** Cannot take actions or reactions. */
	public boolean preventsActions() {
		return switch (this) {
			case INCAPACITATED, PARALYZED, PETRIFIED, STUNNED, UNCONSCIOUS -> true;
			default -> false;
		};
	}

	/** Speed 0: cannot move (see MovementAnchorTracker). */
	public boolean preventsMovement() {
		return switch (this) {
			case GRAPPLED, PARALYZED, PETRIFIED, RESTRAINED, UNCONSCIOUS -> true;
			default -> false;
		};
	}

	/** Every melee hit (within 5 feet) against it is an automatic critical. */
	public boolean autoCritInMelee() {
		return this == PARALYZED || this == UNCONSCIOUS;
	}

	/** Automatically fails Strength and Dexterity saves. */
	public boolean autoFailsStrDexSaves() {
		return switch (this) {
			case PARALYZED, PETRIFIED, STUNNED, UNCONSCIOUS -> true;
			default -> false;
		};
	}

	/** Resistance to all damage (petrified only, in 5e). */
	public boolean resistsAllDamage() {
		return this == PETRIFIED;
	}

	/** Lowercase name used in JSON, commands and network — storage format, not meant for the player to read. */
	public String label() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** English name (5e SRD) for display to the player/DM. {@link #label()} remains the disk/network value. */
	public String displayLabel() {
		return switch (this) {
			case BLINDED -> "Blinded";
			case CHARMED -> "Charmed";
			case DEAFENED -> "Deafened";
			case FRIGHTENED -> "Frightened";
			case GRAPPLED -> "Grappled";
			case INCAPACITATED -> "Incapacitated";
			case INVISIBLE -> "Invisible";
			case PARALYZED -> "Paralyzed";
			case PETRIFIED -> "Petrified";
			case POISONED -> "Poisoned";
			case PRONE -> "Prone";
			case RESTRAINED -> "Restrained";
			case STUNNED -> "Stunned";
			case UNCONSCIOUS -> "Unconscious";
		};
	}

	/** {@code null} if the text doesn't name any condition — a free-form effect ("fire", "bleeding"). */
	@Nullable
	public static Condition fromLabel(String label) {
		if (label == null) return null;
		for (Condition condition : values()) {
			if (condition.label().equalsIgnoreCase(label)) return condition;
		}
		//Sheets, monster NBT and packs written when labels were Spanish still load.
		return LEGACY_SPANISH.get(label.toLowerCase(Locale.ROOT));
	}

	private static final java.util.Map<String, Condition> LEGACY_SPANISH = java.util.Map.ofEntries(
		java.util.Map.entry("cegado", BLINDED), java.util.Map.entry("hechizado", CHARMED),
		java.util.Map.entry("ensordecido", DEAFENED), java.util.Map.entry("asustado", FRIGHTENED),
		java.util.Map.entry("agarrado", GRAPPLED), java.util.Map.entry("incapacitado", INCAPACITATED),
		java.util.Map.entry("paralizado", PARALYZED), java.util.Map.entry("petrificado", PETRIFIED),
		java.util.Map.entry("envenenado", POISONED), java.util.Map.entry("derribado", PRONE),
		java.util.Map.entry("apresado", RESTRAINED), java.util.Map.entry("aturdido", STUNNED),
		java.util.Map.entry("inconsciente", UNCONSCIOUS));
}
