package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * <p>What decides how a saving-throw spell lands on someone, whoever casts it: the cover the terrain
 * gives them, the DC actually rolled against, whether it's beaten, and how much damage ends up applied.</p>
 *
 * <p>The other half of {@link AttackRules}, and for the same reason. This was written twice —
 * {@code SpellCastManager.castSaveSpell} for a player caster and {@code MonsterActionManager.resolveSpell}
 * for a monster caster — and the two copies had already drifted apart: cover only counted on the player
 * side, so taking cover from a dragon's breath, the textbook case for the rule, did nothing.</p>
 *
 * <p>Tolerance for a target with no stat block (a mob from another mod: rolls a plain d20 instead of
 * being excluded from the spell) only existed on the player's path. <b>It wasn't a bug</b> — a monster's
 * spell always targets a player, so its branch never reached that case — but now the rule is a single
 * one, and the day a monster can target something else it already behaves the same way.</p>
 *
 * <p>What's left out is what genuinely differs between the two paths: where the DC comes from (a player
 * computes it from their sheet, a monster carries it written in its stat block), how it's announced, and
 * what status effect hangs off a failed save.</p>
 */
final class SaveRules {

	/**
	 * @param dc  the DC actually rolled against, already with cover subtracted — this is the one that must
	 *            be announced, or the number wouldn't match the result.
	 * @param finalDamage what must be applied: full, half, or none.
	 */
	record Outcome(Cover cover, int dc, Combatant.SaveRoll roll, boolean saved, int finalDamage,
			String damageFormatted, Component label, boolean legendaryResistance) {}

	private SaveRules() {
	}

	/**
	 * <p>Resolves the saving throw. Returns {@code null} if there's nothing to resolve (the die couldn't
	 * be rolled, or the target has nothing to save with), in which case the caller must not announce anything.</p>
	 *
	 * @param baseDc caster's DC, before cover.
	 */
	@Nullable
	static Outcome resolve(Entity caster, Entity target, String saveAbility, int baseDc, String dice, boolean halfOnSave) {
		//Cover boosts DEXTERITY saves and only those: it's dodging that cover helps with, not
		//withstanding poison or resisting suggestion. It's subtracted from the DC instead of added to the
		//roll because it's the same margin either way, and the target might not have a sheet to write a bonus on.
		Cover cover = "dex".equals(saveAbility) ? Cover.between(caster, target) : Cover.NONE;
		int dc = baseDc - cover.bonus();

		//Empty sheet on purpose: a spell's damage is plain dice, with no one's ability score involved.
		DiceManager.RollOutcome damageRoll = DiceManager.roll(new JsonObject(), dice);
		if (damageRoll.result() == null) return null;

		Combatant.SaveRoll saveRoll = rollSave(target, saveAbility);
		if (saveRoll == null || saveRoll.formatted() == null) return null;

		boolean saved = saveRoll.succeeds(dc);
		//Legendary Resistance: a boss that fails can choose not to. Resolved HERE, after rolling and
		//before tallying damage, because that's exactly what it is — turning a failure into a success —
		//and because this is the only place in the mod where whether a save succeeds gets decided. Before
		//unifying the two paths this would have had to be written twice, and the monster copy would have
		//fallen behind like everything else did.
		boolean legendary = false;
		if (!saved && MonsterRegistry.spendLegendaryResistance(target)) {
			saved = true;
			legendary = true;
		}
		int rolled = damageRoll.result().getValue();
		int finalDamage = saved ? (halfOnSave ? rolled / 2 : 0) : rolled;
		Component label = Component.translatable(saved
			? (halfOnSave ? "chat.dndsheets.spell.save_half" : "chat.dndsheets.spell.save_none")
			: "chat.dndsheets.spell.save_fail");

		return new Outcome(cover, dc, saveRoll, saved, finalDamage,
			finalDamage > 0 ? damageRoll.formatted() + " (" + finalDamage + ")" : null, label, legendary);
	}

	@Nullable
	private static Combatant.SaveRoll rollSave(Entity target, String saveAbility) {
		Combatant combatant = Combatant.of(target);
		if (combatant != null) return combatant.rollSave(saveAbility);
		//Player with no sheet loaded: nothing gets resolved. Better to deal no damage than to deal it
		//with made-up ability scores.
		if (target instanceof Player) return null;
		//Mob from another mod with no stat block (see TurnManager.isMonster): there are no ability scores
		//to look up, so it rolls a plain d20. This branch used to exist only on the player's side, so the
		//same mob was immune to monster spells but not to player spells.
		return new Combatant.SaveRoll(DiceManager.roll(new JsonObject(), "1d20"), null);
	}
}
