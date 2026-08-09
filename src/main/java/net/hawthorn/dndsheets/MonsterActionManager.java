package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.network.MonsterActionOpenMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>El DM controla los monstruos sin IA con la Vara de DM ({@link MonsterRegistry#isDmTool}): clic
 * derecho sobre uno abre un menú con sus ataques/hechizos; al elegir uno, se resuelve contra el jugador
 * más cercano al monstruo (tirada de ataque/salvación real, daño real aplicado). Agachado + clic derecho
 * (sobre un monstruo O un armor stand) lo elimina al instante, para limpiar si se invocó de más.</p>
 */
@Mod.EventBusSubscriber
public class MonsterActionManager {

	@SubscribeEvent
	public static void onInteractWithMonster(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		Player dm = event.getEntity();
		if (!MonsterRegistry.isDmTool(dm.getMainHandItem()) && !MonsterRegistry.isDmTool(dm.getOffhandItem())) return;
		if (!dm.hasPermissions(2)) return; //La Vara de DM solo funciona en manos de un op, aunque un jugador la consiga.

		Entity target = event.getTarget();
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(target);
		boolean isArmorStand = target instanceof ArmorStand;
		if (block == null && !isArmorStand) return;

		event.setCanceled(true);

		//Agachado + clic derecho con la Vara de DM: borra al monstruo o armor stand al instante, para
		//limpiar si se invocó de más. Sin agacharse, se comporta como siempre (menú de acciones).
		if (dm.isShiftKeyDown()) {
			Component deletedName = block != null ? Component.literal(block.name()) : Component.translatable("chat.dndsheets.monster.the_armor_stand");
			TurnManager.markDefeated(target.getId()); //Borrado a mano por el DM: ya no es un enemigo en pie, cuenta igual que muerto para el fin automático de combate.
			target.remove(Entity.RemovalReason.DISCARDED);
			if (dm instanceof ServerPlayer serverDm) {
				serverDm.sendSystemMessage(Component.translatable("chat.dndsheets.monster.deleted", deletedName).withStyle(ChatFormatting.GRAY));
			}
			//Si era el último enemigo con vida, el combate termina AHORA, no cuando le vuelva a tocar el
			//turno a alguien (único punto que antes comprobaba esto) — ver TurnManager.checkAllEnemiesDefeated.
			if (target.level() instanceof ServerLevel level) TurnManager.checkAllEnemiesDefeated(level);
			return;
		}

		if (block == null) return; //Los armor stands no tienen menú de acciones, solo se pueden eliminar.
		if (!(dm instanceof ServerPlayer serverDm)) return;

		List<String> customAttackNames = new ArrayList<>();
		for (MonsterRegistry.MonsterAttack attack : MonsterRegistry.customAttacksOf(target)) customAttackNames.add(attack.name());

		List<String> actionNames = new ArrayList<>();
		for (MonsterRegistry.MonsterAttack attack : block.attacks()) actionNames.add(attack.name());
		actionNames.addAll(customAttackNames);
		for (MonsterRegistry.MonsterSpell spell : block.spells()) actionNames.add(spell.name());

		//Ya no se corta aquí si está vacío: el menú siempre se abre, aunque solo sea para usar
		//"+ Añadir ataque" y darle su primera acción a un monstruo recién invocado (p.ej. un NPC genérico).
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> serverDm), new MonsterActionOpenMessage(target.getId(), actionNames, customAttackNames));
	}

	//Carta de invocación (pestaña creativa o /dndspells... análogo): clic derecho en un bloque la invoca
	//encima, igual que un huevo de spawn vanilla. En creativo no se gasta; en supervivencia sí.
	@SubscribeEvent
	public static void onUseSpawnCard(PlayerInteractEvent.RightClickBlock event) {
		if (event.getEntity().level().isClientSide()) return;
		ItemStack stack = event.getItemStack();
		String monsterId = MonsterRegistry.monsterSpawnIdOf(stack);
		if (monsterId == null) return;

		event.setCanceled(true);
		if (!(event.getLevel() instanceof ServerLevel level)) return;

		BlockPos spawnPos = event.getPos().relative(event.getFace());
		Entity spawned = MonsterRegistry.spawnAt(level, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, monsterId);
		if (spawned == null) return;
		CombatFx.monsterSpawn(spawned);

		Player player = event.getEntity();
		if (!player.getAbilities().instabuild) stack.shrink(1);
	}

	/**
	 * <p>Llamado al recibir un {@code MonsterActionChooseMessage}: el DM eligió la acción {@code actionIndex}
	 * (0..N-1 ataques, luego 0..M-1 hechizos) para el monstruo {@code entityId}.</p>
	 */
	public static void resolveAction(ServerPlayer dm, int entityId, int actionIndex) {
		if (actionIndex < 0) return;
		Entity monsterEntity = dm.level().getEntity(entityId);
		if (monsterEntity == null) return;

		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monsterEntity);
		if (block == null) return;

		Player target = monsterEntity.level().getNearestPlayer(monsterEntity, 30);
		if (target == null) return;

		//En modo turnos, un monstruo también gasta su única acción del turno: si el DM insiste en hacerlo
		//actuar de nuevo antes de que le vuelva a tocar, se ignora igual que le pasaría a un jugador.
		if (!TurnManager.tryAct(monsterEntity)) {
			dm.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.dndsheets.monster.cant_act", block.name()).withStyle(ChatFormatting.RED));
			return;
		}

		//Mismo orden que onInteractWithMonster arma el menú: ataques de la especie, luego los personalizados
		//de esta instancia (ver MonsterRegistry.addCustomAttack), luego hechizos.
		List<MonsterRegistry.MonsterAttack> attacks = new ArrayList<>(block.attacks());
		attacks.addAll(MonsterRegistry.customAttacksOf(monsterEntity));

		if (actionIndex < attacks.size()) {
			resolveAttack(block, monsterEntity, attacks.get(actionIndex), target);
			return;
		}

		int spellIndex = actionIndex - attacks.size();
		if (spellIndex < 0 || spellIndex >= block.spells().size()) return;
		resolveSpell(block, monsterEntity, block.spells().get(spellIndex), target);
	}

	//Turno automático del monstruo: TurnManager.beginTurn lo llama en cuanto le toca a un monstruo, sin
	//esperar a la Vara de DM — es la pieza que hace que "sin DM" sea real (ver AUDIT.md sección 0). Ataca
	//al jugador más cercano con su primer ataque disponible (de especie, luego personalizado); si no tiene
	//ataques, prueba con su primer hechizo. ponytail: sin selección táctica de objetivo/ataque (no elige
	//blanco más débil, no varía de ataque, no huye con poca vida) — un monstruo siempre golpea al más
	//cercano de la forma más simple posible. El DM sigue pudiendo intervenir a mano en cualquier otro
	//momento (p.ej. entre rondas) con la Vara de DM de siempre.
	public static void autoAct(ServerLevel level, Entity monsterEntity) {
		if (!monsterEntity.isAlive()) return;
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monsterEntity);
		if (block == null) return;

		//tryAct primero, SIEMPRE, aunque no haya a quién atacar: es lo que le avisa a TurnManager que este
		//combatiente ya gastó su turno y dispara el auto-avance (ver TurnManager.scheduleAutoAdvance). Sin
		//esto, un monstruo sin nadie cerca se quedaría con el turno colgado para siempre — nadie va a
		//escribir /dndturns next por él.
		if (!TurnManager.tryAct(monsterEntity)) return;

		Player target = level.getNearestPlayer(monsterEntity, 30);
		if (target == null) return; //Nadie cerca: pasa el turno sin hacer nada, ya quedó consumido arriba.

		List<MonsterRegistry.MonsterAttack> attacks = new ArrayList<>(block.attacks());
		attacks.addAll(MonsterRegistry.customAttacksOf(monsterEntity));
		if (!attacks.isEmpty()) {
			resolveAttack(block, monsterEntity, attacks.get(0), target);
			return;
		}
		if (!block.spells().isEmpty()) {
			resolveSpell(block, monsterEntity, block.spells().get(0), target);
		}
	}

	//Ataque de oportunidad: TurnManager lo dispara cuando quien tiene el turno sale del alcance cuerpo a
	//cuerpo de un monstruo sin haber usado ya su reacción esta ronda (ver OpportunityAttackTracker.checkOpportunityAttacks).
	//Usa el primer ataque real disponible (de especie o personalizado), sin pasar por resolveAction: esto es
	//una reacción del monstruo, no su acción del turno, así que no toca TurnManager.tryAct.
	public static void resolveOpportunityAttack(Entity monsterEntity, Player mover) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monsterEntity);
		if (block == null) return;

		List<MonsterRegistry.MonsterAttack> attacks = new ArrayList<>(block.attacks());
		attacks.addAll(MonsterRegistry.customAttacksOf(monsterEntity));
		if (attacks.isEmpty()) return;

		ChatFeedback.broadcast(monsterEntity, Component.translatable("chat.dndsheets.monster.opportunity_attack", block.name(), mover.getName().getString()).withStyle(ChatFormatting.DARK_PURPLE));
		resolveAttack(block, monsterEntity, attacks.get(0), mover);
	}

	private static void resolveAttack(MonsterRegistry.MonsterStatBlock block, Entity monsterEntity, MonsterRegistry.MonsterAttack attack, Player target) {
		int toHitMod = block.abilityModifier(attack.toHitAbility()) + block.proficiencyBonus();
		DiceManager.AttackRoll attackRoll = DiceManager.rollAttack(new JsonObject(), "1d20 + " + toHitMod, DiceManager.Advantage.NORMAL);
		if (attackRoll.outcome().result() == null) return;
		CombatFx.diceTick(monsterEntity);

		JsonObject targetSheet = SheetLoader.getServerSheet(target.getStringUUID());
		int targetAc = targetSheet != null ? CombatManager.armorClassOf(target, targetSheet) : 10 + (int) target.getArmorValue();
		if (!attackRoll.criticalHit() && !attackRoll.criticalMiss() && target instanceof ServerPlayer serverTarget) {
			targetAc = ShieldManager.effectiveAc(serverTarget, attackRoll.outcome().result().getValue(), targetAc);
		}

		if (attackRoll.criticalMiss() || (!attackRoll.criticalHit() && attackRoll.outcome().result().getValue() < targetAc)) {
			ChatFeedback.broadcast(monsterEntity, ChatFeedback.attackResult(block.name(), target.getName().getString(), attack.name(), attackRoll.outcome().formatted(), targetAc, false, null));
			return;
		}

		int damageMod = block.abilityModifier(attack.damageAbility());
		DiceManager.DamageResult damageRoll = DiceManager.rollDamage(new JsonObject(), attack.dice() + " + " + damageMod, attackRoll.criticalHit());
		if (damageRoll.formatted() == null) return;

		double affinity = DamageTypes.multiplierFor(target, targetSheet, attack.damageType());
		int finalAmount = DamageTypes.applyMultiplier(damageRoll.amount(), affinity);
		target.hurt(target.damageSources().generic(), finalAmount);
		if (target instanceof ServerPlayer serverTarget) ConcentrationManager.onDamageTaken(serverTarget, finalAmount);
		CombatFx.hit(target, attackRoll.criticalHit());
		ChatFeedback.broadcast(monsterEntity, ChatFeedback.attackResult(block.name(), target.getName().getString(), attack.name(), attackRoll.outcome().formatted(), targetAc, true, damageRoll.formatted()));

		if (attack.appliesEffect()) applyEffectFromHit(target, attack.effectName(), attack.effectDice(), attack.effectTurns());
	}

	//Deja el efecto listo para que TurnManager lo vaya aplicando al empezar cada uno de los turnos del objetivo.
	private static void applyEffectFromHit(Entity target, String name, String dice, int turns) {
		TurnManager.applyEffect(target, name, dice, turns);
		ChatFeedback.broadcast(target, net.minecraft.network.chat.Component.translatable("chat.dndsheets.monster.effect_applied", target.getName().getString(), name, turns).withStyle(ChatFormatting.DARK_PURPLE));
	}

	private static void resolveSpell(MonsterRegistry.MonsterStatBlock block, Entity monsterEntity, MonsterRegistry.MonsterSpell spell, Player target) {
		JsonObject targetSheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (targetSheet == null) return;

		String counterer = CounterspellManager.findCounterer(monsterEntity.level(), monsterEntity.position(), monsterEntity);
		if (counterer != null) {
			ChatFeedback.broadcast(monsterEntity, Component.translatable("chat.dndsheets.spell.counterspelled", block.name(), spell.name(), counterer).withStyle(ChatFormatting.DARK_PURPLE));
			return;
		}

		DiceManager.RollOutcome saveRoll = DiceManager.roll(targetSheet, "1d20 + $" + spell.saveAbility());
		if (saveRoll.result() == null) return;

		DiceManager.RollOutcome damageRoll = DiceManager.roll(new JsonObject(), spell.dice());
		if (damageRoll.result() == null) return;

		boolean saved = saveRoll.result().getValue() >= spell.saveDc();
		int finalDamage = saved ? (spell.halfOnSave() ? damageRoll.result().getValue() / 2 : 0) : damageRoll.result().getValue();
		Component outcomeLabel = Component.translatable(saved ? (spell.halfOnSave() ? "chat.dndsheets.spell.save_half" : "chat.dndsheets.spell.save_none") : "chat.dndsheets.spell.save_fail");

		CombatFx.spellCast(monsterEntity);
		CombatFx.spellImpact(target, saved);
		ChatFeedback.broadcast(monsterEntity, ChatFeedback.saveResult(block.name(), target.getName().getString(), spell.name(), saveRoll.formatted(), spell.saveDc(), saved, outcomeLabel,
			finalDamage > 0 ? damageRoll.formatted() + " (" + finalDamage + ")" : null));

		if (finalDamage > 0) {
			double affinity = DamageTypes.multiplierFor(target, targetSheet, spell.damageType());
			int appliedAmount = DamageTypes.applyMultiplier(finalDamage, affinity);
			target.hurt(target.damageSources().generic(), appliedAmount);
			if (target instanceof ServerPlayer serverTarget) ConcentrationManager.onDamageTaken(serverTarget, appliedAmount);
		}
		if (finalDamage > 0 && spell.appliesEffect()) applyEffectFromHit(target, spell.effectName(), spell.effectDice(), spell.effectTurns());
	}
}
