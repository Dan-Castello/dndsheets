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
 * <p>Empujar: la acción especial de cuerpo a cuerpo de 5e (sustituye un ataque, no es magia ni un rasgo de
 * clase) que trae la táctica de "ventaja por altura + empujón" de Baldur's Gate 3 — agregada a pedido
 * explícito, junto con {@link AttackRules#advantageAgainst} (altura). Contested check: Atletismo (Fuerza)
 * de quien empuja contra lo mejor entre Atletismo y Acrobacias de quien lo recibe, igual que el SRD.</p>
 *
 * <p><b>Simplificaciones deliberadas</b> frente al SRD completo:</p>
 * <ul>
 *   <li>Solo característica, sin competencia de habilidad: el bestiario no trae Atletismo/Acrobacias por
 *       monstruo, así que exigirla habría dejado la mitad de los objetivos posibles sin poder defenderse
 *       con su propio número. {@code ponytail}: si algún día el bloque de estadísticas trae habilidades,
 *       sumarla aquí es un solo cambio.</li>
 *   <li>El resultado es SIEMPRE empujar (nunca derribar): en la mesa lo elige quien empuja; aquí, para no
 *       necesitar una segunda pantalla de elección a mitad de un clic, se fija al que de verdad trae la
 *       fantasía de BG3 (tirar a alguien de una cornisa), y es el que además combina con la ventaja por
 *       altura que se acaba de agregar.</li>
 *   <li>El empujón es un impulso de físicas (puede tirar a alguien por un borde), no los 5 pies exactos del
 *       SRD medidos con regla — a propósito: es lo que hace que empujar desde terreno alto sea peligroso
 *       de verdad, que es justo la mecánica que se pidió traer.</li>
 * </ul>
 */
class ShoveManager {
	//Mismo criterio que un clic de ataque cuerpo a cuerpo: lo bastante cerca para considerarse "al alcance",
	//sin medir 5 pies exactos (el mod no tiene grilla de combate real, ver PROJECT_CONTEXT.md).
	private static final double MELEE_REACH = 4.0;
	private static final float PUSH_STRENGTH = 0.9f;
	private static final float PUSH_UP = 0.35f;

	//Se activa desde AbilityItemDispatcher, igual que Marca del Cazador: necesita un objetivo concreto, así
	//que vive en el evento EntityInteract y no en los otros dos.
	static void tryUse(PlayerInteractEvent.EntityInteract event) {
		InteractionEvents.consume(event);
		if (!(event.getEntity() instanceof ServerPlayer attacker)) return;
		Entity targetEntity = event.getTarget();

		if (attacker.distanceTo(targetEntity) > MELEE_REACH) {
			attacker.sendSystemMessage(Component.translatable("chat.dndsheets.shove.too_far").withStyle(ChatFormatting.GRAY));
			return;
		}
		Combatant target = Combatant.of(targetEntity);
		if (target == null) return; //Sin representación en las reglas: nada que resolver.

		//Empujar sustituye UN ataque del turno — mismo gasto de acción que golpear.
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
		//Mismo punto que quien empuja (arriba/abajo en línea recta): empuja a un rumbo fijo en vez de
		//dividir por cero.
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
