package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>Shove: the 5e melee special action (replaces an attack, it's neither magic nor a class feature) that
 * brings Baldur's Gate 3's "high-ground advantage + shove" tactic — added on explicit request, together
 * with {@link AttackRules#advantageAgainst} (height). Contested check: the shover's Athletics (Strength)
 * against the higher of the target's Athletics and Acrobatics, same as the SRD.</p>
 *
 * <p><b>Deliberate simplifications</b> compared to the full SRD:</p>
 * <ul>
 *   <li>Ability score only, no skill proficiency: the bestiary doesn't carry Athletics/Acrobatics per
 *       monster, so requiring it would have left half the possible targets unable to defend with their own
 *       number. {@code ponytail}: if the stat block ever carries skills, adding it here is a single change.</li>
 *   <li>The outcome is ALWAYS a shove (never a knockdown): at the table the shover picks; here, to avoid
 *       needing a second choice screen mid-click, it's fixed to the one that actually delivers the BG3
 *       fantasy (knocking someone off a ledge), and it's also the one that combines with the height
 *       advantage just added.</li>
 *   <li>The push is a physics impulse (can knock someone off an edge), not the SRD's exact 5 feet measured
 *       with a ruler — on purpose: it's what makes shoving from high ground genuinely dangerous, which is
 *       exactly the mechanic that was requested.</li>
 * </ul>
 */
class ShoveManager {
	//Same criterion as a melee attack click: close enough to count as "in reach", without measuring exact
	//5 feet (the mod has no real combat grid, see PROJECT_CONTEXT.md).
	private static final double MELEE_REACH = 4.0;
	private static final float PUSH_STRENGTH = 0.9f;
	private static final float PUSH_UP = 0.35f;

	//Triggered from AbilityItemDispatcher, same as Hunter's Mark: it needs a concrete target, so it lives
	//in the EntityInteract event and not the other two.
	static void tryUse(PlayerInteractEvent.EntityInteract event) {
		InteractionEvents.consume(event);
		if (!(event.getEntity() instanceof ServerPlayer attacker)) return;
		Entity targetEntity = event.getTarget();

		if (attacker.distanceTo(targetEntity) > MELEE_REACH) {
			attacker.sendSystemMessage(Component.translatable("chat.dndsheets.shove.too_far").withStyle(ChatFormatting.GRAY));
			return;
		}
		Combatant target = Combatant.of(targetEntity);
		if (target == null) return; //No representation in the rules: nothing to resolve.

		//Shoving replaces ONE attack of the turn — same action cost as hitting.
		if (!TurnManager.tryAct(attacker)) {
			TurnManager.notifyCantAct(attacker);
			return;
		}

		JsonObject attackerSheet = SheetLoader.getServerSheet(attacker.getStringUUID());
		Combatant attackerCombatant = attackerSheet == null ? null : new Combatant.PlayerCombatant(attacker, attackerSheet);
		int attackMod = attackerCombatant != null ? attackerCombatant.abilityModifier("str") : 0;
		int defenseMod = Math.max(target.abilityModifier("str"), target.abilityModifier("dex"));

		DiceManager.RollOutcome attackRoll = DiceManager.roll(new JsonObject(), "1d20 + " + attackMod);
		DiceManager.RollOutcome defenseRoll = DiceManager.roll(new JsonObject(), "1d20 + " + defenseMod);
		if (attackRoll.result() == null || defenseRoll.result() == null) return;

		String attackerName = attackerSheet != null ? SheetLoader.characterNameOf(attackerSheet, attacker) : attacker.getGameProfile().getName();
		boolean success = attackRoll.result().getValue() > defenseRoll.result().getValue();

		Component message = Component.translatable(success ? "chat.dndsheets.shove.success" : "chat.dndsheets.shove.fail",
			attackerName, target.name(), attackRoll.formatted(), defenseRoll.formatted())
			.withStyle(success ? ChatFormatting.GOLD : ChatFormatting.GRAY);
		ChatFeedback.broadcast(attacker, message);
		if (!success) return;

		push(attacker, targetEntity);
		CombatFx.hit(targetEntity, false);
	}

	private static void push(ServerPlayer attacker, Entity targetEntity) {
		if (!(targetEntity instanceof LivingEntity target)) return;

		Vec3 direction = target.position().subtract(attacker.position());
		double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
		//Same spot as the shover (straight up/down): pushes toward a fixed heading instead of dividing by zero.
		double nx = horizontal < 1.0E-4 ? 0 : direction.x / horizontal;
		double nz = horizontal < 1.0E-4 ? 1 : direction.z / horizontal;

		Vec3 velocity = target.getDeltaMovement();
		target.setDeltaMovement(velocity.x + nx * PUSH_STRENGTH, Math.max(velocity.y, PUSH_UP), velocity.z + nz * PUSH_STRENGTH);
	}

	public static ItemStack buildShoveStack() {
		return AbilityItem.build(ItemLook.SHOVE, "shove", Component.translatable("chat.dndsheets.shove.item_name"),
			Component.translatable("chat.dndsheets.shove.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
