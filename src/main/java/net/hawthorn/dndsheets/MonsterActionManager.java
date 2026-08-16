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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
		//event.getItemStack() es el objeto de LA MANO DE ESTE EVENTO, no "cualquiera de las dos". El
		//cliente lanza un evento por mano (Minecraft.startUseItem recorre InteractionHand.values() y
		//reintenta con la otra si la primera no consume), asi que mirar ambas manos hacia que el
		//manejador corriese DOS veces por clic — el mensaje de chat duplicado. Sigue valiendo llevar la
		//vara en la mano secundaria: entonces la pasada que coincide es la de esa mano.
		if (!MonsterRegistry.isDmTool(event.getItemStack())) return;
		if (!dm.hasPermissions(2)) return; //La Vara de DM solo funciona en manos de un op, aunque un jugador la consiga.

		Entity target = event.getTarget();
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(target);
		boolean isArmorStand = target instanceof ArmorStand;
		if (block == null && !isArmorStand) return;

		InteractionEvents.consume(event);

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

	//Vara de Movimiento: reposicionar un monstruo ya invocado sin pasar por su menú de ataques ni tener que
	//esperar a que le toque el turno — para montar la escena (o corregir dónde cayó al invocarlo) sin
	//comandos ni coordenadas a mano. Selección en memoria por jugador: clic derecho en un monstruo lo
	//selecciona (sobreescribe la selección anterior si había una), clic derecho en un bloque lo mueve ahí.
	private static final Map<UUID, Integer> pendingMove = new HashMap<>();

	@SubscribeEvent
	public static void onSelectMonsterToMove(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		Player dm = event.getEntity();
		//event.getItemStack() es el objeto de LA MANO DE ESTE EVENTO, no "cualquiera de las dos". El
		//cliente lanza un evento por mano (Minecraft.startUseItem recorre InteractionHand.values() y
		//reintenta con la otra si la primera no consume), asi que mirar ambas manos hacia que el
		//manejador corriese DOS veces por clic — el mensaje de chat duplicado. Sigue valiendo llevar la
		//vara en la mano secundaria: entonces la pasada que coincide es la de esa mano.
		if (!MonsterRegistry.isMoveTool(event.getItemStack())) return;
		if (!dm.hasPermissions(2)) return;

		Entity target = event.getTarget();
		if (MonsterRegistry.statBlockOf(target) == null) return;

		InteractionEvents.consume(event);
		pendingMove.put(dm.getUUID(), target.getId());
		if (dm instanceof ServerPlayer serverDm) {
			serverDm.sendSystemMessage(Component.translatable("chat.dndsheets.monster.move_selected", target.getName().getString()).withStyle(ChatFormatting.GRAY));
		}
	}

	@SubscribeEvent
	public static void onSelectMoveDestination(PlayerInteractEvent.RightClickBlock event) {
		if (event.getEntity().level().isClientSide()) return;
		Player dm = event.getEntity();
		//event.getItemStack() es el objeto de LA MANO DE ESTE EVENTO, no "cualquiera de las dos". El
		//cliente lanza un evento por mano (Minecraft.startUseItem recorre InteractionHand.values() y
		//reintenta con la otra si la primera no consume), asi que mirar ambas manos hacia que el
		//manejador corriese DOS veces por clic — el mensaje de chat duplicado. Sigue valiendo llevar la
		//vara en la mano secundaria: entonces la pasada que coincide es la de esa mano.
		if (!MonsterRegistry.isMoveTool(event.getItemStack())) return;

		Integer entityId = pendingMove.get(dm.getUUID());
		if (entityId == null) return;

		InteractionEvents.consume(event);
		pendingMove.remove(dm.getUUID());
		if (!(event.getLevel() instanceof ServerLevel level)) return;

		Entity monster = level.getEntity(entityId);
		if (monster == null || !monster.isAlive()) return; //Ya no existe (muerto/borrado desde que se seleccionó): nada que mover.

		BlockPos destination = event.getPos().relative(event.getFace());
		monster.teleportTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5);
		if (dm instanceof ServerPlayer serverDm) {
			serverDm.sendSystemMessage(Component.translatable("chat.dndsheets.monster.moved", monster.getName().getString()).withStyle(ChatFormatting.GRAY));
		}
	}

	//Carta de invocación (pestaña creativa o /dndspells... análogo): clic derecho en un bloque la invoca
	//encima, igual que un huevo de spawn vanilla. En creativo no se gasta; en supervivencia sí.
	@SubscribeEvent
	public static void onUseSpawnCard(PlayerInteractEvent.RightClickBlock event) {
		if (event.getEntity().level().isClientSide()) return;
		ItemStack stack = event.getItemStack();
		String monsterId = MonsterRegistry.monsterSpawnIdOf(stack);
		if (monsterId == null) return;

		InteractionEvents.consume(event);
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
	 * (0..N-1 ataques, luego 0..M-1 hechizos) para el monstruo {@code entityId}, y a quién apuntarla
	 * ({@code targetUuid}, elegido en {@link net.hawthorn.dndsheets.client.gui.MonsterActionScreen} vía
	 * {@link net.hawthorn.dndsheets.client.gui.PlayerPickerScreen}).</p>
	 */
	public static void resolveAction(ServerPlayer dm, int entityId, int actionIndex, String targetUuid) {
		if (actionIndex < 0) return;
		Entity monsterEntity = dm.level().getEntity(entityId);
		if (monsterEntity == null) return;

		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monsterEntity);
		if (block == null) return;

		Player target = resolveTarget(dm, monsterEntity, targetUuid);
		if (target == null) return;

		//En modo turnos, un monstruo también gasta su única acción del turno: si el DM insiste en hacerlo
		//actuar de nuevo antes de que le vuelva a tocar, se ignora igual que le pasaría a un jugador.
		if (!TurnManager.tryAct(monsterEntity)) {
			dm.sendSystemMessage(net.minecraft.network.chat.Component.translatable("chat.dndsheets.monster.cant_act", block.name()).withStyle(ChatFormatting.RED));
			return;
		}

		//Mismo acercamiento que ya usa autoAct (turno automático sin DM) — sin esto, un monstruo controlado
		//a mano con la Vara de DM atacaba siempre desde donde apareció, sin acercarse nunca (NoAI fijo desde
		//que se invoca, ver MonsterRegistry.spawnAt), aunque el jugador estuviera fuera de su alcance.
		moveTowardIfNeeded(monsterEntity, target);

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

	//Antes esto SIEMPRE resolvía contra level.getNearestPlayer, sin dejarle al DM elegir a quién de verdad
	//quería apuntar (ni revisar línea de visión) — un DM no podía hacer que el ogro se ensañara con el
	//pícaro que lo insultó en vez de con el tanque más cercano. Cae al más cercano solo si el UUID llega
	//vacío/inválido o ese jugador ya no está conectado (elegido en el picker, pero se desconectó antes de
	//que el mensaje llegara) — mejor un objetivo razonable que ninguno.
	private static Player resolveTarget(ServerPlayer dm, Entity monsterEntity, String targetUuid) {
		if (targetUuid != null && !targetUuid.isEmpty()) {
			try {
				ServerPlayer target = dm.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(targetUuid));
				if (target != null) return target;
			} catch (IllegalArgumentException ignored) {
				//UUID malformado: nunca debería pasar viniendo del picker, pero cualquier cliente puede
				//mandar cualquier string — cae al más cercano en vez de tumbar la resolución.
			}
		}
		return monsterEntity.level().getNearestPlayer(monsterEntity, 30);
	}

	//Turno automático del monstruo: TurnManager.beginTurn lo llama en cuanto le toca a un monstruo, sin
	//esperar a la Vara de DM — es la pieza que hace que "sin DM" sea real. Ataca al jugador más cercano con
	//un ataque elegido al azar entre los disponibles (de especie + personalizados); si no tiene ataques,
	//prueba con un hechizo al azar entre los suyos. ponytail: el azar solo decide QUÉ ataque usa, sin
	//selección táctica de objetivo (no elige blanco más débil, no huye con poca vida) — un monstruo siempre
	//golpea al más cercano. El DM sigue pudiendo intervenir a mano en cualquier otro momento (p.ej. entre
	//rondas) con la Vara de DM de siempre.
	public static void autoAct(ServerLevel level, Entity monsterEntity) {
		if (!monsterEntity.isAlive()) return;
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monsterEntity);
		if (block == null) return;

		//tryAct primero, SIEMPRE, aunque no haya a quién atacar: es lo que le avisa a TurnManager que este
		//combatiente ya gastó su turno y dispara el auto-avance (ver TurnManager.scheduleAutoAdvance). Sin
		//esto, un monstruo sin nadie cerca se quedaría con el turno colgado para siempre — nadie va a
		//escribir /dndturns next por él.
		if (!TurnManager.tryAct(monsterEntity)) return;

		//Una invocación del jugador ataca a los enemigos de su dueño; un monstruo del DM, al jugador más
		//cercano. Sin esta distinción, el Arma Espiritual le pegaba a quien la invocó.
		Entity target = SummonManager.ownerOf(monsterEntity) != null
			? SummonManager.findEnemyTarget(level, monsterEntity, 30)
			: level.getNearestPlayer(monsterEntity, 30);
		if (target == null) return; //Nadie cerca: pasa el turno sin hacer nada, ya quedó consumido arriba.

		moveTowardIfNeeded(monsterEntity, target);

		List<MonsterRegistry.MonsterAttack> attacks = new ArrayList<>(block.attacks());
		attacks.addAll(MonsterRegistry.customAttacksOf(monsterEntity));
		if (!attacks.isEmpty()) {
			resolveAttack(block, monsterEntity, randomOf(attacks), target);
			return;
		}
		//Los hechizos de monstruo siguen exigiendo un jugador: su resolución lee la hoja del objetivo.
		if (!block.spells().isEmpty() && target instanceof Player playerTarget) {
			resolveSpell(block, monsterEntity, randomOf(block.spells()), playerTarget);
		}
	}

	//Elige entre varias opciones (ataques o hechizos) al azar en vez de siempre la primera — así un
	//monstruo con "mordisco" y "garra" no repite el mismo golpe en cada turno. Con una sola opción,
	//nextInt(1) siempre da 0: no hace falta un caso especial para ese tamaño.
	private static <T> T randomOf(List<T> options) {
		return options.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(options.size()));
	}

	//Antes el monstruo atacaba desde donde estuviera parado, sin importar la distancia real al objetivo
	//(NoAI fijo desde que se invoca, ver MonsterRegistry.spawnAt) — nunca podía perseguir a nadie que se
	//alejara, ni flanquear, ni siquiera acercarse a golpear: le pegaba a cualquiera hasta 30 bloques como
	//si tuviera alcance infinito. Se acerca en línea recta hasta el alcance de melé (mismo valor que ya
	//usa OpportunityAttackTracker) antes de resolver su acción, con el mismo presupuesto de movimiento por
	//turno que ya usan los mobs de compatibilidad (ver MovementAnchorTracker.speedBlocksForMob).
	//ponytail: línea recta en el plano horizontal, sin pathfinding real (no esquiva obstáculos, no rodea
	//paredes) — sigue siendo NoAI de verdad, esto es solo simular "se acercó", no IA de movimiento real.
	private static void moveTowardIfNeeded(Entity monster, Entity target) {
		Vec3 from = monster.position();
		Vec3 to = target.position();
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
		if (horizontalDistance <= OpportunityAttackTracker.MELEE_REACH) return;

		double travel = Math.min(MovementAnchorTracker.speedBlocksForMob(monster), horizontalDistance - OpportunityAttackTracker.MELEE_REACH);
		if (travel <= 0) return;

		double scale = travel / horizontalDistance;
		monster.teleportTo(from.x + dx * scale, from.y, from.z + dz * scale);
		MonsterRegistry.faceTarget(monster, target);
	}

	//Ataque de oportunidad: TurnManager lo dispara cuando quien tiene el turno sale del alcance cuerpo a
	//cuerpo de un monstruo sin haber usado ya su reacción esta ronda (ver OpportunityAttackTracker.checkOpportunityAttacks).
	//Usa un ataque real al azar entre los disponibles (de especie o personalizado), sin pasar por
	//resolveAction: esto es una reacción del monstruo, no su acción del turno, así que no toca TurnManager.tryAct.
	/**
	 * <p>Un ataque de acción legendaria: mismo camino que el de oportunidad —una de sus armas, resuelta con
	 * las reglas de siempre— con su propio aviso, porque en el chat hay que poder distinguir por qué el jefe
	 * acaba de pegar fuera de su turno.</p>
	 */
	public static void resolveLegendaryAttack(Entity monsterEntity, Player target) {
		attackOutsideOwnTurn(monsterEntity, target, "chat.dndsheets.monster.legendary_action");
	}

	public static void resolveOpportunityAttack(Entity monsterEntity, Player mover) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monsterEntity);
		if (block == null) return;

		List<MonsterRegistry.MonsterAttack> attacks = new ArrayList<>(block.attacks());
		attacks.addAll(MonsterRegistry.customAttacksOf(monsterEntity));
		if (attacks.isEmpty()) return;

		attackOutsideOwnTurn(monsterEntity, mover, "chat.dndsheets.monster.opportunity_attack");
	}

	//Cuerpo común de los dos ataques fuera de turno: la única diferencia real entre un ataque de oportunidad
	//y uno legendario es qué dice el chat, y tenerlo escrito dos veces era pedir que se separaran.
	private static void attackOutsideOwnTurn(Entity monsterEntity, Player target, String messageKey) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monsterEntity);
		if (block == null) return;

		List<MonsterRegistry.MonsterAttack> attacks = new ArrayList<>(block.attacks());
		attacks.addAll(MonsterRegistry.customAttacksOf(monsterEntity));
		if (attacks.isEmpty()) return;

		Combatant combatant = Combatant.of(target);
		String targetName = combatant != null ? combatant.name() : target.getName().getString();
		ChatFeedback.broadcast(monsterEntity, Component.translatable(messageKey, block.name(), targetName).withStyle(ChatFormatting.DARK_PURPLE));
		resolveAttack(block, monsterEntity, randomOf(attacks), target);
	}

	/**
	 * <p>Objetivo {@code Entity} y no {@code Player}: un invocado del jugador (Arma Espiritual, Esfera
	 * Flamígera) ataca a monstruos, no a jugadores. Todo lo específico del objetivo —CA, resistencias,
	 * PG temporales, cómo recibe el daño— ya lo resuelve {@link Combatant}, así que generalizarlo no
	 * costó una segunda rama sino borrar las que quedaban.</p>
	 */
	private static void resolveAttack(MonsterRegistry.MonsterStatBlock block, Entity monsterEntity, MonsterRegistry.MonsterAttack attack, Entity target) {
		Combatant targetCombatant = Combatant.of(target);
		if (targetCombatant == null) return;

		int toHitMod = block.abilityModifier(attack.toHitAbility()) + block.proficiencyBonus();
		//Se asume cuerpo a cuerpo: un bloque de estadísticas no dice el alcance de sus ataques, y el monstruo
		//se acerca hasta MELEE_REACH antes de pegar (ver moveTowardIfNeeded).
		boolean melee = true;
		//Un monstruo no trae ventaja propia todavía (no tiene hoja ni flags), así que solo pasa lo del
		//objetivo. El día que la traiga, entra por el mismo sitio y se combina con todo lo demás de una vez.
		DiceManager.AttackRoll attackRoll = DiceManager.rollAttack(new JsonObject(), "1d20 + " + toHitMod,
			AttackRules.advantageAgainst(targetCombatant, melee));
		if (attackRoll.outcome().result() == null) return;
		CombatFx.diceTick(monsterEntity);

		//El monstruo juega con las MISMAS reglas que el jugador, y ahora literalmente con el mismo código:
		//cobertura, CA efectiva, acierto y crítico salen de AttackRules. Que esto estuviera escrito dos veces
		//es lo que dejó a los monstruos ignorando la ventaja por estado y la cobertura, cada una durante
		//meses y descubierta por separado.
		AttackRules.Against result = AttackRules.against(monsterEntity, targetCombatant, attackRoll, melee);
		int targetAc = result.targetAc();
		String targetName = targetCombatant.name();

		if (!result.hit()) {
			ChatFeedback.broadcast(monsterEntity, ChatFeedback.withCover(ChatFeedback.attackResult(block.name(), targetName, attack.name(), attackRoll.outcome().formatted(), targetAc, false, null), result.cover()));
			return;
		}

		boolean critical = result.critical();
		int damageMod = block.abilityModifier(attack.damageAbility());
		DiceManager.DamageResult damageRoll = DiceManager.rollDamage(new JsonObject(), attack.dice() + " + " + damageMod, critical);
		if (damageRoll.formatted() == null) return;

		//Un ataque natural de monstruo no es mágico salvo que su bloque lo diga, y el esquema todavía no lo dice.
		int finalAmount = DamageTypes.applyMultiplier(damageRoll.amount(), targetCombatant.effectiveDamageMultiplier(attack.damageType(), false));
		targetCombatant.takeDamage(finalAmount); //Cubre PG temporales, concentración y muerte en un solo sitio.
		CombatFx.hit(target, critical, attack.damageType());
		ChatFeedback.broadcast(monsterEntity, ChatFeedback.withCover(ChatFeedback.attackResult(block.name(), targetName, attack.name(), attackRoll.outcome().formatted(), targetAc, true, damageRoll.formatted()), result.cover()));

		if (attack.appliesEffect()) applyEffectFromHit(target, attack.effectName(), attack.effectDice(), attack.effectTurns(), monsterEntity);
	}

	//Deja el efecto listo para que TurnManager lo vaya aplicando al empezar cada uno de los turnos del objetivo.
	private static void applyEffectFromHit(Entity target, String name, String dice, int turns, Entity source) {
		TurnManager.applyEffect(target, name, dice, turns, source);
		//Nombre del personaje, no el de la cuenta de Minecraft: es el mismo criterio que el resto de líneas
		//de combate, y aquí se colaba el otro. Sin Combatant (un mob de compatibilidad) queda el de siempre.
		Combatant combatant = Combatant.of(target);
		String targetName = combatant != null ? combatant.name() : target.getName().getString();
		ChatFeedback.broadcast(target, net.minecraft.network.chat.Component.translatable("chat.dndsheets.monster.effect_applied", targetName, name, turns).withStyle(ChatFormatting.DARK_PURPLE));
	}

	private static void resolveSpell(MonsterRegistry.MonsterStatBlock block, Entity monsterEntity, MonsterRegistry.MonsterSpell spell, Player target) {
		JsonObject targetSheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (targetSheet == null) return;

		String counterer = CounterspellManager.findCounterer(monsterEntity.level(), monsterEntity.position(), monsterEntity);
		if (counterer != null) {
			ChatFeedback.broadcast(monsterEntity, Component.translatable("chat.dndsheets.spell.counterspelled", block.name(), spell.name(), counterer).withStyle(ChatFormatting.DARK_PURPLE));
			return;
		}

		Combatant targetCombatant = Combatant.of(target);
		if (targetCombatant == null) return;

		//Cobertura, CD real, salvación y daño final: mismas reglas, y mismo código, que cuando el que lanza
		//es un jugador (ver SaveRules). La CD sí sale de sitios distintos — el monstruo la trae escrita en su
		//bloque y el jugador la calcula de su hoja — y esa diferencia es real, no duplicación.
		SaveRules.Outcome save = SaveRules.resolve(monsterEntity, target, spell.saveAbility(),
			spell.saveDc(), spell.dice(), spell.halfOnSave());
		if (save == null) return;
		boolean saved = save.saved();

		CombatFx.spellCast(monsterEntity);
		CombatFx.spellImpact(target, saved, spell.damageType());
		//targetCombatant.name() y no target.getName(): el resto del mod anuncia el nombre del PERSONAJE, y
		//aquí se colaba el de la cuenta de Minecraft.
		ChatFeedback.broadcast(monsterEntity, ChatFeedback.withLegendaryResistance(
			ChatFeedback.withCover(ChatFeedback.saveResult(block.name(), targetCombatant.name(), spell.name(),
				save.roll().formatted(), save.dc(), saved, save.label(), save.damageFormatted()), save.cover()),
			save.legendaryResistance(), MonsterRegistry.legendaryResistancesLeft(target)));

		//Una sola implementación de "aplicar daño de conjuro": afinidades, PG temporales, concentración y
		//muerte. Un conjuro siempre cuenta como mágico.
		if (save.finalDamage() > 0) SpellCastManager.applyDamage(target, save.finalDamage(), spell.damageType());
		//Mismo criterio que SpellCastManager.castSaveSpell: la condición la decide la salvación, no el daño.
		//Un aliento paralizante que no hace daño debe paralizar igual, y uno que sí lo hace no debe imponer
		//su condición a quien superó la tirada.
		if (!saved && spell.appliesEffect()) applyEffectFromHit(target, spell.effectName(), spell.effectDice(), spell.effectTurns(), monsterEntity);
	}
}
