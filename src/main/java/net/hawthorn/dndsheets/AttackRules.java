package net.hawthorn.dndsheets;

import net.minecraft.world.entity.Entity;

/**
 * <p>What decides whether an attack hits, no matter who's attacking: the advantage granted by the
 * target's state, terrain cover, effective AC after defensive reactions, and whether the hit is
 * critical.</p>
 *
 * <p><b>Why this exists.</b> That logic was written <em>twice</em>, once in {@code CombatManager} (a
 * player attacks) and once in {@code MonsterActionManager} (a monster attacks), and the two copies drifted
 * apart three times in a row, always in the same direction: the new rule got written into the player's
 * path and the monster's stayed behind.</p>
 *
 * <ul>
 *   <li>A monster attacked with a fixed {@code Advantage.NORMAL}, so it missed out on half of what
 *       conditions do: "attacks against you have advantage" is half the definition of prone, restrained,
 *       paralyzed, blinded and unconscious.</li>
 *   <li>Cover was only applied when a player attacked, so a barricade let monsters hide from players and
 *       never the other way around.</li>
 *   <li>Dodging was about to repeat the same story as soon as it was added.</li>
 * </ul>
 *
 * <p>All three were fixed one at a time, which is exactly the sign that fixing them individually wasn't
 * the real fix. With a single function, the next rule enters both paths by construction, not by
 * remembering to add it.</p>
 *
 * <p>What does <b>not</b> belong here is what actually distinguishes the two: how the roll is built (a
 * player adds ability and proficiency from their sheet, a monster its fixed modifier) and how the damage
 * is built (weapons, sneak attack, smite... versus a stat block's die). Those are real differences, not
 * duplication.</p>
 */
final class AttackRules {

	/**
	 * @param targetAc the AC actually compared against, already including cover and reactions — this is
	 *                 what must be announced in chat, or the number wouldn't match the result.
	 * @param cover    for the chat note: a miss against an AC higher than the monster's stat block says
	 *                 reads as a mod bug if it isn't explained why.
	 */
	record Against(int targetAc, Cover cover, boolean hit, boolean critical) {}

	private AttackRules() {
	}

	/**
	 * <p>Advantage or disadvantage for the whole attack: what the target's state contributes, whether it
	 * spent its action Dodging, and whatever the attacker brings ({@code fromAttacker}: its own pending
	 * advantage, its own conditions...).</p>
	 *
	 * <p><b>All combined at once, not piecemeal.</b> The 5e rule is that with at least one source of
	 * advantage and at least one of disadvantage you end up with neither, <em>no matter how many of each
	 * there are</em>, and {@link DiceManager#combineAdvantage} implements exactly that. But for that same
	 * reason it <b>cannot be nested</b>: combining the target's sources first and feeding the result into
	 * another combination turns an advantage and a disadvantage that canceled out into a "normal"
	 * indistinguishable from "nothing", and then the attacker's advantage wins on its own. A prone target
	 * (advantage in melee) that is also Dodging (disadvantage), attacked by someone with pending advantage:
	 * the correct answer is normal, and nesting produced advantage instead. That's why the attacker's
	 * sources are folded in here rather than combined outside.</p>
	 */
	static DiceManager.Advantage advantageAgainst(Entity attacker, Combatant target, boolean melee, DiceManager.Advantage... fromAttacker) {
		DiceManager.Advantage[] sources = new DiceManager.Advantage[fromAttacker.length + 3];
		sources[0] = target.advantageAgainst(melee);
		//Dodging is resolved here and not inside advantageAgainst because it isn't a condition of the
		//target, it's an action it spent this round: Combatant doesn't know about turns and shouldn't.
		sources[1] = TurnActionManager.isDodging(target.entity()) ? DiceManager.Advantage.DISADVANTAGE : DiceManager.Advantage.NORMAL;
		sources[2] = heightAdvantage(attacker, target.entity());
		System.arraycopy(fromAttacker, 0, sources, 3, fromAttacker.length);
		return DiceManager.combineAdvantage(sources);
	}

	//Not an SRD rule: it's Baldur's Gate 3's "height advantage", requested on purpose so combat rewards
	//taking the high ground the same way that game does. Symmetric — attacking well from above grants
	//advantage, attacking well from below grants disadvantage — and it enters through the same spot as
	//the other sources, so it applies whether a player or a monster attacks without having to repeat it
	//on both sides (see the rationale for this whole class, above).
	private static final double HEIGHT_THRESHOLD_BLOCKS = 2.0;

	private static DiceManager.Advantage heightAdvantage(Entity attacker, Entity target) {
		//null in tests with no world behind them (FakeCombatant.entity()) and, in the real game, any
		//Combatant that doesn't come from a real entity: with no height to compare, it contributes nothing.
		if (attacker == null || target == null) return DiceManager.Advantage.NORMAL;
		double diff = attacker.getY() - target.getY();
		if (diff >= HEIGHT_THRESHOLD_BLOCKS) return DiceManager.Advantage.ADVANTAGE;
		if (diff <= -HEIGHT_THRESHOLD_BLOCKS) return DiceManager.Advantage.DISADVANTAGE;
		return DiceManager.Advantage.NORMAL;
	}

	/**
	 * <p>Resolves the already-rolled attack against the target: cover, effective AC, hit and critical.</p>
	 *
	 * @param melee whether the attack is melee, which changes two things in 5e: a prone target is easier
	 *              to hit in melee and harder at range, and the automatic critical against a paralyzed or
	 *              unconscious target only happens in melee.
	 */
	static Against against(Entity attacker, Combatant target, DiceManager.AttackRoll roll, boolean melee) {
		Cover cover = Cover.between(attacker, target.entity());
		int value = roll.outcome().result().getValue();

		int targetAc = target.armorClass() + cover.bonus();
		//Defensive reactions (Shield): only worth asking about if the hit actually depends on AC — a
		//critical hit always lands and a critical miss always fails, with or without Shield. The reaction
		//has cover subtracted from the ROLL instead of added to the AC: it's the same margin, and this way
		//Shield keeps deciding based on its own number without knowing anything about cover.
		if (!roll.criticalHit() && !roll.criticalMiss()) {
			targetAc = target.reactiveArmorClass(value - cover.bonus()) + cover.bonus();
		}

		boolean hit = roll.criticalHit() || (!roll.criticalMiss() && value >= targetAc);
		//5e's automatic critical: any melee hit against a paralyzed or unconscious target is a critical,
		//even if the d20 didn't roll a 20.
		boolean critical = roll.criticalHit() || (hit && melee && target.autoCritInMelee());
		return new Against(targetAc, cover, hit, critical);
	}
}
