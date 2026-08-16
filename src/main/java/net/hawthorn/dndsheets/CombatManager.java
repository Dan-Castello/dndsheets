package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;

/**
 * <p>Dos usos: (1) los armor stands actúan como muñecos de pruebas, golpearlos (cuerpo a cuerpo o a
 * distancia) con un arma configurada hace la tirada de daño sola y la anuncia en el chat, sin destruir
 * el muñeco; (2) en PvP, un golpe entre jugadores con un arma configurada se resuelve como un ataque de
 * 5e de verdad: tirada de ataque (1d20 + característica + competencia) contra la CA real del objetivo
 * (10 + mod. Destreza + armadura real equipada) y, si impacta, el daño real se reemplaza por la tirada
 * de daño configurada. Si el arma no está configurada, no se toca nada y Minecraft se comporta como siempre.</p>
 */
@Mod.EventBusSubscriber
public class CombatManager {

	private record Roll(int amount, String formatted, String weaponName, String characterName) {}
	//autoDetected: no-null solo cuando el ítem no está registrado a mano (JSON/.toml) pero declara daño de
	//ataque real por atributo vanilla (ver Config.autoDetectWeapon) — compatibilidad con armas de otros
	//mods (Tinkers' Construct y cualquier otro) sin necesitar un JSON por ítem.
	private record IdentifiedWeapon(String id, String name, int enchantBonus, Config.WeaponDefault autoDetected) {}
	private record ResolvedWeapon(String dice, String ability, String damageType) {}

	//Id virtual para el golpe a mano desnuda cuando un rasgo le da un dado real (ver TraitRegistry):
	//no es un arma configurada en Config, así que se resuelve aparte en resolveWeapon/findWeaponExpression.
	private static final String UNARMED_ID = "dndsheets:unarmed";

	//Ventaja/desventaja se fija con /dndsheet advantage y se consume solo (vuelve a "normal") en la
	//siguiente tirada de ataque de esa hoja, sea con arma, hechizo (ver SpellCastManager) o el botón de
	//ataque de la propia hoja (ver procedures.RollAnnouncerProcedure) — público por eso, no solo del paquete.
	public static DiceManager.Advantage consumeAdvantage(JsonObject sheet) {
		DiceManager.Advantage advantage = DiceManager.advantageFromLabel(sheet.has("nextAttackAdvantage") ? sheet.get("nextAttackAdvantage").getAsString() : "normal");
		sheet.addProperty("nextAttackAdvantage", "normal");
		return advantage;
	}

	@SubscribeEvent
	public static void onAttackEntity(AttackEntityEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		Player player = event.getEntity();
		Entity target = event.getTarget();

		if (target instanceof ArmorStand) {
			event.setCanceled(true); //Muñeco de pruebas: no se destruye ni se desarma al golpearlo.
			ItemStack heldItem = player.getMainHandItem();
			if (heldItem.isEmpty()) return;
			Roll roll = computeDamageRoll(player, identifyWeapon(player, heldItem));
			if (roll != null) announce(target, player, roll);
			return;
		}

		if (TurnManager.isCombatTarget(target)) {
			if (Combatant.of(target) == null) {
				//Jefe/enemigo de otro mod, sin representación en las reglas (ni bloque ni ficha): no
				//hay tirada de ataque/daño 5e que resolver, pero el golpe igual engancha el modo turnos
				//(arranca el combate solo, cuenta como tu acción, bloquea golpear fuera de turno) — el daño
				//real lo sigue resolviendo Minecraft tal cual.
				autoStartCombatIfNeeded(target, player);
				if (!TurnManager.tryAct(player)) {
					event.setCanceled(true);
					TurnManager.notifyCantAct(player);
				}
				return;
			}

			//El arma se identifica ANTES de tocar el turno (arma sin configurar no debería gastar nada), pero
			//a diferencia de antes ya NO se sale sin más si es null: un puñetazo sin rasgo de golpe desnudo
			//(sin Artes Marciales ni Forma Salvaje) sigue siendo LA acción del jugador este turno — antes
			//quedaba fuera del todo del modo turnos (nunca arrancaba el combate, nunca gastaba el turno),
			//así que un jugador sin monje podía pegar puñetazos indefinidamente sin que "sin DM" se enterara.
			IdentifiedWeapon weapon = identifyWeapon(player, player.getMainHandItem());
			if (weapon != null && blockedByOffhand(weapon, player.getOffhandItem().isEmpty())) {
				event.setCanceled(true); //Cancela de verdad: ni siquiera el golpe flojo de Minecraft pasa, no se puede empuñar así.
				player.sendSystemMessage(Component.translatable("chat.dndsheets.combat.needs_both_hands").withStyle(ChatFormatting.RED));
				return; //No fue un intento de ataque real: no arranca combate ni gasta el turno.
			}
			if (weapon != null && blockedByClass(player, weapon)) {
				event.setCanceled(true);
				player.sendSystemMessage(Component.translatable("chat.dndsheets.combat.wrong_class").withStyle(ChatFormatting.RED));
				return;
			}
			if (blockedByCharm(player, target)) {
				event.setCanceled(true);
				player.sendSystemMessage(Component.translatable("chat.dndsheets.condition.charmed_block").withStyle(ChatFormatting.RED));
				return; //No fue un intento de ataque válido: no arranca combate ni gasta el turno.
			}
			autoStartCombatIfNeeded(target, player);
			if (!TurnManager.tryAct(player)) {
				event.setCanceled(true); //Fuera de turno: ni siquiera el puñetazo flojo de Minecraft pasa.
				TurnManager.notifyCantAct(player);
				return;
			}
			if (weapon == null) return; //Sin arma ni rasgo de golpe desnudo: Minecraft resuelve el golpe normal, turno ya gastado.
			event.setCanceled(true); //Se resuelve como un encuentro real, no como el golpe de Minecraft.
			resolveAttackOnCreature(player, target, weapon, true);
		}
	}

	//Sin nadie llevando la partida en vivo, nadie va a escribir /dndturns start (ni sumar a mano a quien
	//llega tarde): el primer golpe de un jugador a un monstruo arranca el combate si no había uno activo,
	//mismo punto de entrada que ya usa el Panel de DM (TurnManager.startAt). Quien da ese golpe entra como
	//INICIADOR y abre el orden de turnos: antes no, y el resultado era que su ataque se perdía si no ganaba
	//su propia tirada de iniciativa — el mismo clic funcionaba o desaparecía según un d20 que nadie había
	//pedido tirar (ver TurnManager.startAt con iniciador).
	//Si el combate YA estaba activo pero este jugador nunca entró al orden (llegó después de que
	//arrancara), se suma ahora mismo — sin esto se quedaba sin poder actuar nunca en ese encuentro. Ahí NO
	//es iniciador: el encuentro ya existía y llegar tarde no da derecho a colarse el primero.
	//Público: también lo usa SpellCastManager, para que atacar con un hechizo arranque el combate solo
	//igual que ya hace un golpe con arma — antes un hechizo de ataque/salvación se resolvía "gratis", sin
	//turno ni congelamiento para nadie, porque nada lo llamaba desde ese lado.
	public static void autoStartCombatIfNeeded(Entity target, Player attacker) {
		if (!(target.level() instanceof ServerLevel level)) return;
		if (!TurnManager.isActive()) {
			TurnManager.startAt(level, target.position(), TurnManager.DEFAULT_RADIUS, attacker);
			return;
		}
		if (attacker instanceof ServerPlayer serverPlayer) TurnManager.addLatePlayerIfMissing(level, serverPlayer);
	}

	//Identifica el arma a distancia mirando qué lleva el jugador en las manos en el momento del impacto
	//(bastante fiable, arcos y ballestas no se sueltan de la mano al disparar). Para proyectiles que sí
	//abandonan la mano (p.ej. un tridente lanzado), cae a un segundo intento por el tipo de entidad del
	//proyectil: no lleva el ítem original, así que no ve sus encantamientos ni una etiqueta NBT
	//personalizada, solo el dado por defecto configurado.
	@SubscribeEvent
	public static void onProjectileImpact(ProjectileImpactEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		HitResult ray = event.getRayTraceResult();
		if (!(ray instanceof EntityHitResult entityHit)) return;
		Entity target = entityHit.getEntity();
		if (!(event.getEntity() instanceof Projectile projectile) || !(projectile.getOwner() instanceof Player player)) return;

		if (target instanceof ArmorStand) {
			event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY); //Muñeco de pruebas: no se destruye al recibir el disparo.
			IdentifiedWeapon weapon = identifyRangedWeapon(player, projectile);
			if (weapon == null) return;
			Roll roll = computeDamageRoll(player, weapon);
			if (roll != null) announce(target, player, roll);
			return;
		}

		if (TurnManager.isCombatTarget(target)) {
			if (Combatant.of(target) == null) {
				//Mismo criterio de compatibilidad que onAttackEntity: sin representación en las reglas, el
				//disparo sigue enganchando el modo turnos, pero el daño real lo resuelve Minecraft tal cual.
				autoStartCombatIfNeeded(target, player);
				if (!TurnManager.tryAct(player)) {
					event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
					TurnManager.notifyCantAct(player);
				}
				return;
			}

			//Mismo orden que onAttackEntity: identificar el arma antes de cancelar el evento/gastar el turno.
			IdentifiedWeapon weapon = identifyRangedWeapon(player, projectile);
			if (weapon == null) return; //Proyectil no reconocido: Minecraft se comporta como siempre.
			if (blockedByClass(player, weapon)) {
				player.sendSystemMessage(Component.translatable("chat.dndsheets.combat.wrong_class").withStyle(ChatFormatting.RED));
				return; //Ni se resuelve como 5e ni se toca el turno: el disparo vanilla ya salió antes del impacto.
			}
			if (blockedByCharm(player, target)) {
				event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY);
				player.sendSystemMessage(Component.translatable("chat.dndsheets.condition.charmed_block").withStyle(ChatFormatting.RED));
				return;
			}
			event.setImpactResult(ProjectileImpactEvent.ImpactResult.SKIP_ENTITY); //Se resuelve como un encuentro real, no como el impacto de Minecraft.
			autoStartCombatIfNeeded(target, player);
			if (!TurnManager.tryAct(player)) { TurnManager.notifyCantAct(player); return; }
			resolveAttackOnCreature(player, target, weapon, false);
		}
	}

	//PvP: se resuelve como un ataque real de 5e. Si el arma no está configurada, no se toca nada y
	//Minecraft aplica su daño normal de siempre (nada de esto interfiere con peleas "normales").
	@SubscribeEvent
	public static void onLivingHurt(LivingHurtEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof Player victim)) return; //La víctima debe ser un jugador (PvP).

		DamageSource source = event.getSource();
		if (!(source.getEntity() instanceof Player attacker)) return; //Quien golpea también debe ser un jugador.

		//El arma se identifica ANTES de tocar el turno: si no está configurada, esto ni siquiera es un
		//ataque de 5e (puede ser una explosión o una poción lanzada antes que Forge atribuye al jugador) y
		//no debe gastar su acción ni bloquearse por el gating de turno — antes se llamaba a tryAct primero,
		//así que cualquier daño atribuido al jugador consumía su turno aunque no fuera un golpe real.
		boolean melee = !(source.getDirectEntity() instanceof Projectile);
		IdentifiedWeapon weapon = source.getDirectEntity() instanceof Projectile projectile
			? identifyRangedWeapon(attacker, projectile)
			: identifyWeapon(attacker, attacker.getMainHandItem());
		if (weapon == null) return; //Arma no reconocida: se deja el daño normal de Minecraft, sin tocar el turno.
		if (melee && blockedByOffhand(weapon, attacker.getOffhandItem().isEmpty())) {
			event.setCanceled(true); //Cancela de verdad: no se puede empuñar así, ni siquiera el daño normal de Minecraft pasa.
			attacker.sendSystemMessage(Component.translatable("chat.dndsheets.combat.needs_both_hands").withStyle(ChatFormatting.RED));
			return;
		}
		if (blockedByCharm(attacker, victim)) {
			if (melee) event.setCanceled(true); //Cuerpo a cuerpo sí se puede cancelar a tiempo; una flecha ya en vuelo, no.
			attacker.sendSystemMessage(Component.translatable("chat.dndsheets.condition.charmed_block").withStyle(ChatFormatting.RED));
			return;
		}
		if (blockedByClass(attacker, weapon)) {
			if (melee) event.setCanceled(true); //Cuerpo a cuerpo sí se puede cancelar a tiempo; una flecha ya en vuelo, no.
			attacker.sendSystemMessage(Component.translatable("chat.dndsheets.combat.wrong_class").withStyle(ChatFormatting.RED));
			return;
		}

		JsonObject attackerSheet = SheetLoader.getServerSheet(attacker.getStringUUID());
		Combatant target = Combatant.of(victim);
		if (attackerSheet == null || target == null) return;

		//weaponDefault también se comprueba ANTES de tocar el turno: identifyWeapon devuelve un
		//IdentifiedWeapon no-nulo para CUALQUIER ítem no vacío en la mano, esté o no dado de alta en
		//Config — así que "sostener una espada sin configurar" pasaba el filtro de arriba igual, y solo
		//acá se detectaba que no era un arma real. Antes tryAct se llamaba primero, así que ese golpe sin
		//configurar gastaba la acción del turno para nada, aunque el daño cayera a vanilla de todos modos.
		ResolvedWeapon weaponDefault = resolveWeapon(attacker, attackerSheet, weapon, SheetLoader.characterLevelOf(attackerSheet, attacker));
		if (weaponDefault == null) return; //Arma no configurada: se deja el daño normal de Minecraft, sin tocar el turno.

		//En modo turnos, ni el PvP con arma configurada se libra: golpear fuera de tu turno (o dos veces en
		//el mismo turno) se bloquea del todo, no solo se deja de resolver como ataque de 5e (si no,
		//Minecraft aplicaría su daño normal de todos modos).
		if (!TurnManager.tryAct(attacker)) {
			event.setCanceled(true);
			TurnManager.notifyCantAct(attacker);
			return;
		}

		AttackOutcome outcome = resolveAttack(attacker, attackerSheet, target, weapon,
			weaponDefault.ability(), weaponDefault.damageType(), melee);
		if (outcome == null) return;
		if (!outcome.hit()) {
			event.setCanceled(true); //Fallo: ni siquiera se aplica la reducción de daño de la armadura de Minecraft, no hay golpe.
			ChatFeedback.broadcast(attacker, outcome.message());
			return;
		}

		//El daño se entrega por el propio evento, NO por Combatant.takeDamage: ya estamos DENTRO de la
		//tubería de daño de Minecraft y llamarlo aquí recurriría. A partir de este punto la armadura real
		//del objetivo todavía puede restar algo más — Minecraft lo hace solo después de este evento.
		//Los PG temporales se descuentan aquí a mano: este camino entrega el daño por el propio evento, así
		//que no puede pasar por Combatant.takeDamage (recurriría). Ver Combatant.absorbWithTemporaryHp.
		int afterTemporary = target.absorbWithTemporaryHp(outcome.damage());
		event.setAmount(afterTemporary);
		ConcentrationManager.onDamageTaken((ServerPlayer) victim, afterTemporary);
		ChatFeedback.broadcast(attacker, outcome.message());
	}

	private record AttackOutcome(boolean hit, int damage, MutableComponent message) {}

	/**
	 * <p>Núcleo compartido de toda tirada de ataque de un jugador contra un {@link Combatant}, sea otro
	 * jugador o un monstruo. Antes esto eran dos métodos casi idénticos —este camino de PvP y
	 * {@code resolveAttackOnCreature}— que solo se diferenciaban en cómo leían la CA, los PG y el nombre del
	 * objetivo; y esa diferencia se había llevado por delante, sin que nadie lo decidiera, las resistencias,
	 * la reacción de Escudo y la concentración del lado del monstruo.</p>
	 *
	 * <p>No aplica el daño a propósito: lo devuelve. Los dos llamadores lo entregan por caminos que no se
	 * pueden unificar sin romper algo — el PvP está dentro del {@code LivingHurtEvent} de Minecraft y usa
	 * {@code setAmount}, mientras que el monstruo lleva sus PG de 5e aparte del atributo de salud vanilla.</p>
	 */
	private static AttackOutcome resolveAttack(Player attacker, JsonObject attackerSheet, Combatant target,
			IdentifiedWeapon weapon, String ability, String damageType, boolean melee) {
		//Todas las fuentes en UNA sola llamada, nunca combinadas por partes: ver AttackRules.advantageAgainst.
		DiceManager.Advantage advantage = AttackRules.advantageAgainst(target, melee,
			consumeAdvantage(attackerSheet),
			new Combatant.PlayerCombatant(attacker, attackerSheet).ownAttackAdvantage());

		String expression = "1d20 + $" + ability + " + $prof";
		int inspiration = BardInspirationManager.consumeAttackBonus(attackerSheet);
		if (inspiration > 0) expression = expression + " + " + inspiration;
		DiceManager.AttackRoll attackRoll = DiceManager.rollAttack(attackerSheet, expression, advantage);
		if (attackRoll.outcome().result() == null) return null;
		CombatFx.diceTick(attacker);
		sendSheetUpdate(attacker);

		String attackerName = SheetLoader.characterNameOf(attackerSheet, attacker);
		//Cobertura, CA efectiva, acierto y crítico: mismas reglas, y mismo código, que cuando ataca un
		//monstruo. Ver AttackRules — estar escrito dos veces es lo que dejó a los monstruos ignorando media
		//docena de reglas, cada una descubierta por separado.
		AttackRules.Against result = AttackRules.against(attacker, target, attackRoll, melee);
		int targetAc = result.targetAc();

		if (!result.hit()) {
			return new AttackOutcome(false, 0, ChatFeedback.withCover(ChatFeedback.attackResult(attackerName, target.name(), weapon.name(),
				attackRoll.outcome().formatted(), targetAc, false, null, inspiration), result.cover()));
		}

		boolean critical = result.critical();
		Roll damageRoll = computeDamageRoll(attacker, weapon, critical, advantage, ability, target.entity());
		if (damageRoll == null) return null;

		//Un golpe de arma cuenta como mágico si el arma lleva algún encantamiento que el mod reconozca
		//(ver Config.enchantBonusPerLevelFor). Es la única señal de "arma mágica" que existe en Minecraft
		//sin inventar un material nuevo, y es la que decide media docena de resistencias del SRD.
		boolean magical = weapon.enchantBonus() != 0;
		int finalAmount = DamageTypes.applyMultiplier(damageRoll.amount(), target.effectiveDamageMultiplier(damageType, magical));
		CombatFx.hit(target.entity(), critical, damageType);
		return new AttackOutcome(true, finalAmount, ChatFeedback.withCover(ChatFeedback.attackResult(attackerName, target.name(), weapon.name(),
			attackRoll.outcome().formatted(), targetAc, true, damageRoll.formatted(), inspiration), result.cover()));
	}

	//Jugador ataca a un monstruo invocado por /dndmonsters spawn: mismo ataque-vs-CA que en PvP, pero el
	//objetivo no tiene hoja, tiene un bloque de estadísticas (MonsterRegistry), y sí lleva PG reales
	//trackeados en su NBT persistente en vez de infinitos como el armor stand.
	private static void resolveAttackOnCreature(Player attacker, Entity targetEntity, IdentifiedWeapon weapon, boolean melee) {
		if (weapon == null) return;
		Combatant target = Combatant.of(targetEntity);
		if (target == null) return;
		MonsterRegistry.faceTarget(targetEntity, attacker); //Que quede claro a quién le está respondiendo, acierte o no.

		JsonObject attackerSheet = SheetLoader.getServerSheet(attacker.getStringUUID());
		if (attackerSheet == null) return;

		ResolvedWeapon weaponDefault = resolveWeapon(attacker, attackerSheet, weapon, SheetLoader.characterLevelOf(attackerSheet, attacker));
		String ability = weaponDefault != null ? weaponDefault.ability() : "str";
		String damageType = weaponDefault != null ? weaponDefault.damageType() : "contundente";

		AttackOutcome outcome = resolveAttack(attacker, attackerSheet, target, weapon, ability, damageType, melee);
		if (outcome == null) return;
		if (!outcome.hit()) {
			ChatFeedback.broadcast(attacker, outcome.message());
			return;
		}

		//Los PG restantes se calculan ANTES de aplicar el daño, porque takeDamage puede matar a la entidad
		//(y entonces currentHp ya no diría nada útil). El sufijo de PG sigue siendo solo del lado del
		//monstruo a propósito: en PvP la armadura real de Minecraft resta más daño DESPUÉS del evento, así
		//que cualquier número que anunciáramos ahí sería mentira.
		int remainingHp = Math.max(0, target.currentHp() - outcome.damage());
		MutableComponent message = outcome.message()
			.append(Component.translatable("chat.dndsheets.combat.hp_suffix", remainingHp, target.maxHp()).withStyle(ChatFormatting.DARK_GRAY));

		target.takeDamage(outcome.damage()); //Si llega a 0, mata al mob y lo saca del orden de turnos — ver Combatant.MonsterCombatant.
		if (target.isDefeated()) {
			//Sufijo en la MISMA línea del golpe que acaba de anunciarse, no una segunda línea de chat aparte.
			message.append(Component.translatable("chat.dndsheets.combat.defeated_suffix", target.name()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
		}
		ChatFeedback.broadcast(attacker, message);
	}

	//Bono de CA de llevar un escudo de verdad (+2 en 5e): player.getArmorValue() vanilla NUNCA lo cuenta —
	//solo suma los 4 slots de armadura real, un escudo en la mano secundaria no es "armadura" para
	//Minecraft, ni el vanilla ni uno modded. Se detecta por clase (ShieldItem), no por id, para que
	//funcione igual con el escudo de cualquier mod que extienda la clase vanilla (patrón habitual).
	private static final int SHIELD_AC_BONUS = 2;

	//Público: también lo usa network.SheetSummaryRequestMessage para mostrar la CA real en el Panel de DM.
	public static int armorClassOf(Player player, JsonObject sheet) {
		//Override de DM/OP (/dndsheet setac, ver SheetCommand): manda sobre el cálculo normal para un caso
		//especial (objeto mágico, regla de mesa puntual) sin tener que mentirle a Minecraft sobre la
		//armadura real equipada. "auto" (el caso por defecto) quita esto y vuelve al cálculo de siempre.
		if (sheet != null && sheet.has("armorClassOverride")) return sheet.get("armorClassOverride").getAsInt();
		int shieldBonus = player.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem ? SHIELD_AC_BONUS : 0;
		return 10 + abilityModifier(sheet, "dexterity") + (int) player.getArmorValue() + shieldBonus;
	}

	static int abilityModifier(JsonObject sheet, String key) {
		if (!sheet.has(key)) return 0;
		try {
			return Math.floorDiv(Integer.parseInt(sheet.get(key).getAsString()) - 10, 2);
		} catch (RuntimeException e) {
			//RuntimeException, no solo NumberFormatException: sheet.get(key) puede ser un JsonObject/JsonArray
			//si una hoja vieja quedó corrupta antes de que SheetServerMessage empezara a validar tipos, y
			//.getAsString() sobre eso lanza UnsupportedOperationException, no NumberFormatException.
			return 0;
		}
	}

	private static ItemStack findHeldWeapon(Player player) {
		for (ItemStack candidate : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
			if (candidate.isEmpty()) continue;
			if (Config.weaponDefaultFor(Config.weaponIdOf(candidate)) != null) return candidate;
		}
		return null;
	}

	private static IdentifiedWeapon identifyWeapon(Player player, ItemStack heldItem) {
		if (heldItem == null || heldItem.isEmpty()) return identifyUnarmed(player);
		String itemId = Config.weaponIdOf(heldItem);
		//Solo se molesta en calcular la detección automática si el ítem no está registrado a mano — un
		//registro explícito (JSON/.toml) siempre manda, así que no vale la pena leer atributos si no hace falta.
		Config.WeaponDefault autoDetected = Config.weaponDefaultFor(itemId) == null ? Config.autoDetectWeapon(heldItem) : null;
		return new IdentifiedWeapon(itemId, heldItem.getHoverName().getString(), enchantmentBonusFor(heldItem), autoDetected);
	}

	//Un puñetazo solo se resuelve como ataque real de 5e si el personaje tiene un rasgo que le dé un dado
	//propio (p.ej. Artes Marciales del monje) o Forma Salvaje está activa; sin eso, se queda como el golpe
	//flojo de Minecraft de siempre, igual que cualquier arma sin configurar.
	private static IdentifiedWeapon identifyUnarmed(Player player) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (unarmedProfileFor(player, sheet, SheetLoader.characterLevelOf(sheet, player)) == null) return null;
		return new IdentifiedWeapon(UNARMED_ID, "Golpe", 0, null);
	}

	//Forma Salvaje (temporal, ver DruidWildShapeManager) manda sobre cualquier rasgo permanente de
	//TraitRegistry mientras esté activa — no debería poder pasar que se sumen los dos a la vez.
	private static TraitRegistry.UnarmedProfile unarmedProfileFor(Player player, JsonObject sheet, int level) {
		if (player instanceof ServerPlayer serverPlayer && DruidWildShapeManager.isShifted(serverPlayer)) {
			return DruidWildShapeManager.unarmedProfile();
		}
		return TraitRegistry.unarmedProfileFor(sheet, level);
	}

	private static ResolvedWeapon resolveWeapon(Player player, JsonObject sheet, IdentifiedWeapon weapon, int level) {
		if (UNARMED_ID.equals(weapon.id())) {
			TraitRegistry.UnarmedProfile profile = unarmedProfileFor(player, sheet, level);
			return profile == null ? null : new ResolvedWeapon(profile.dice(), profile.ability(), "contundente");
		}
		Config.WeaponDefault weaponDefault = Config.weaponDefaultFor(weapon.id());
		if (weaponDefault == null) weaponDefault = weapon.autoDetected(); //Compatibilidad con armas de otros mods, ver Config.autoDetectWeapon.
		if (weaponDefault == null) return null;
		//Pacto de la Hoja (ver /dndsheet pact): el brujo usa Carisma para atacar y dañar con su arma, en vez
		//de la característica normal del arma — la diferencia mecánica real de este pacto.
		String ability = "hoja".equals(pactOf(sheet)) ? "cha" : weaponDefault.ability();
		return new ResolvedWeapon(weaponDefault.dice(), ability, weaponDefault.damageType());
	}

	//Un arma "two" (a dos manos de verdad, p.ej. mandoble) no se puede empuñar con nada más en la otra
	//mano — a diferencia de la versátil, que solo pierde el dado grande y sigue atacando igual, esta
	//directamente no ataca. Antes esto no se comprobaba en ningún lado (simplificación deliberada
	//documentada en AUDIT.md) y llevar un escudo con un mandoble atacaba igual, solo con el dado chico.
	private static boolean blockedByOffhand(IdentifiedWeapon weapon, boolean offhandEmpty) {
		if (offhandEmpty || UNARMED_ID.equals(weapon.id())) return false;
		Config.WeaponDefault weaponDefault = Config.weaponDefaultFor(weapon.id());
		if (weaponDefault == null) weaponDefault = weapon.autoDetected();
		return weaponDefault != null && "two".equals(weaponDefault.hands());
	}

	//Arma restringida por clase (campo opcional "classes" en weapons.json, ver Config.WeaponDefault): sin
	//lista configurada, cualquier clase puede usarla, igual que siempre. La mano desnuda nunca se restringe
	//por esto — Artes Marciales/Forma Salvaje ya deciden por su cuenta quién puede pegar a mano.
	private static boolean blockedByClass(Player player, IdentifiedWeapon weapon) {
		if (UNARMED_ID.equals(weapon.id())) return false;
		Config.WeaponDefault weaponDefault = Config.weaponDefaultFor(weapon.id());
		if (weaponDefault == null) weaponDefault = weapon.autoDetected();
		if (weaponDefault == null) return false;
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		String characterClass = sheet != null && sheet.has("characterClass") ? sheet.get("characterClass").getAsString() : null;
		return !weaponDefault.allowsClass(characterClass);
	}

	/**
	 * <p>Hechizado: no puedes atacar a quien te hechizó, pero sí a cualquier otro. Se comprueba junto al
	 * resto de restricciones de empuñadura/clase de este archivo, y no dentro de {@code resolveAttack},
	 * porque hay que decidirlo ANTES de gastar el turno o dejar pasar el daño vanilla.</p>
	 */
	private static boolean blockedByCharm(Player attacker, Entity target) {
		Combatant combatant = Combatant.of(attacker);
		return combatant != null && combatant.cannotAttack(target);
	}

	private static String pactOf(JsonObject sheet) {
		return sheet != null && sheet.has("warlockPact") ? sheet.get("warlockPact").getAsString() : null;
	}

	//Para proyectiles que ya no están en la mano (tridentes lanzados): identifica el arma por el tipo de
	//entidad del proyectil, ya que Minecraft coincidentemente usa el mismo id ("minecraft:trident") para
	//el ítem y para la entidad arrojada.
	//Mismas estadísticas que ya trae por defecto el arco/ballesta vanilla (ver dndsheets-common.toml), para
	//cualquier arco/ballesta modded que no tenga una entrada explícita. Config.autoDetectWeapon (cuerpo a
	//cuerpo) no sirve acá: lee el atributo de daño de ataque, que un arco no tiene — su daño sale de la
	//flecha, no de un atributo del arma.
	private static final Config.WeaponDefault GENERIC_BOW_DEFAULT = new Config.WeaponDefault("1d8", "dex", "fisico", "two", null, java.util.List.of());

	//Reconoce un arco/ballesta de OTRO mod por su propia clase vanilla (la mayoría de mods de arcos extiende
	//BowItem/CrossbowItem para heredar el tensado y disparo) en vez de exigir un id exacto en la config —
	//mismo espíritu que Config.autoDetectWeapon, pero por tipo de ítem en vez de por atributo.
	private static ItemStack findGenericBowOrCrossbow(Player player) {
		for (ItemStack candidate : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
			if (candidate.getItem() instanceof net.minecraft.world.item.BowItem || candidate.getItem() instanceof net.minecraft.world.item.CrossbowItem) return candidate;
		}
		return null;
	}

	private static IdentifiedWeapon identifyRangedWeapon(Player player, Projectile projectile) {
		ItemStack weapon = findHeldWeapon(player);
		if (weapon != null) return identifyWeapon(player, weapon);

		ItemStack genericBow = findGenericBowOrCrossbow(player);
		if (genericBow != null && Config.weaponDefaultFor(Config.weaponIdOf(genericBow)) == null) {
			return new IdentifiedWeapon(Config.weaponIdOf(genericBow), genericBow.getHoverName().getString(), enchantmentBonusFor(genericBow), GENERIC_BOW_DEFAULT);
		}

		String projectileId = ForgeRegistries.ENTITY_TYPES.getKey(projectile.getType()).toString();
		if (Config.weaponDefaultFor(projectileId) == null) return null;
		return new IdentifiedWeapon(projectileId, projectile.getDisplayName().getString(), 0, null);
	}

	private static Roll computeDamageRoll(Player player, IdentifiedWeapon weapon) {
		return computeDamageRoll(player, weapon, false, DiceManager.Advantage.NORMAL, null, null);
	}

	private static Roll computeDamageRoll(Player player, IdentifiedWeapon weapon, boolean critical, DiceManager.Advantage advantage, String attackAbility, Entity target) {
		if (weapon == null) return null;
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return null;
		int level = SheetLoader.characterLevelOf(sheet, player);

		String expression = findWeaponExpression(player, sheet, weapon, level, player.getOffhandItem().isEmpty());
		if (expression == null) return null; //Not a recognized weapon, nothing to roll.

		if (weapon.enchantBonus() != 0) expression = expression + " + " + weapon.enchantBonus();

		//Furia del bárbaro: bono plano de daño cuerpo a cuerpo con Fuerza (ver BarbarianRageManager). Un
		//número plano, no un dado — seguro de concatenar en la misma expresión (a diferencia de Ataque
		//Furtivo, que sí es un dado y necesita tirarse aparte).
		if ("str".equals(attackAbility) && player instanceof ServerPlayer ragingCandidate && BarbarianRageManager.isRaging(ragingCandidate)) {
			expression = expression + " + " + CharacterRules.rageDamageBonusFor(level);
		}

		DiceManager.DamageResult damage = DiceManager.rollDamage(sheet, expression, critical);
		if (damage.formatted() == null) return null;

		int amount = damage.amount();
		String formatted = damage.formatted();

		//Ataque Furtivo (y cualquier rasgo futuro "dado extra con ventaja"): se tira APARTE y se suma en
		//Java, no metido en la misma expresión — el motor de dados (dicebot) no resuelve bien dos grupos
		//de dados distintos en una sola expresión (p.ej. "1d8 + 2d6"), solo grupo+número plano. Se le pasa
		//el mismo "critical" para que también doble sus propios dados en un golpe crítico, como en 5e.
		if (advantage == DiceManager.Advantage.ADVANTAGE) {
			String sneakAttackDice = TraitRegistry.sneakAttackDiceFor(sheet, level);
			if (sneakAttackDice != null) {
				DiceManager.DamageResult sneak = DiceManager.rollDamage(sheet, sneakAttackDice, critical);
				if (sneak.formatted() != null) {
					amount += sneak.amount();
					formatted = formatted + " + Furtivo " + sneak.formatted();
				}
			}
		}

		//Marca del Cazador: mismo "tirar aparte y sumar" que Ataque Furtivo, solo si ESTE golpe cae sobre
		//el objetivo marcado (no cualquiera).
		if (target != null && player instanceof ServerPlayer possibleRanger && RangerHunterMarkManager.isMarked(possibleRanger, target)) {
			DiceManager.DamageResult mark = DiceManager.rollDamage(sheet, RangerHunterMarkManager.DICE, critical);
			if (mark.formatted() != null) {
				amount += mark.amount();
				formatted = formatted + " + Marca " + mark.formatted();
			}
		}

		//Castigo Divino: solo en golpes de arma de verdad, no a mano desnuda NI contra un muñeco de pruebas
		//(target==null es la ruta del armor stand, ver announce/computeDamageRoll(player, weapon) de 2
		//args) — sin el chequeo de target, un swing de práctica contra el dummy gastaba el espacio de
		//conjuro de verdad exactamente igual que un golpe de combate real. Gasta el espacio de conjuro
		//dentro de consumeIfPending, así que si no queda ninguno simplemente no aporta nada (y el flag ya
		//se limpió, no se queda pendiente para el siguiente golpe).
		if (target != null && !UNARMED_ID.equals(weapon.id())) {
			String smiteDice = PaladinSmiteManager.consumeIfPending(sheet, target);
			if (smiteDice != null) {
				DiceManager.DamageResult smite = DiceManager.rollDamage(sheet, smiteDice, critical);
				if (smite.formatted() != null) {
					amount += smite.amount();
					formatted = formatted + " + Castigo Divino " + smite.formatted();
				}
			}
		}

		//Buff de arma con duración (Favor Divino): a diferencia de Castigo Divino NO se consume, se aplica a
		//todos los golpes mientras dure. Se tira aparte por lo mismo que el resto de dados extra: el motor
		//de dados no resuelve dos grupos distintos en una sola expresión.
		WeaponBuffManager.Buff buff = WeaponBuffManager.active(sheet);
		if (buff != null) {
			DiceManager.DamageResult extra = DiceManager.rollDamage(sheet, buff.dice(), critical);
			if (extra.formatted() != null) {
				amount += extra.amount();
				formatted = formatted + " + " + buff.name() + " " + extra.formatted();
			}
		}

		String characterName = SheetLoader.characterNameOf(sheet, player);
		return new Roll(amount, formatted, weapon.name(), characterName);
	}

	private static void announce(Entity target, Player player, Roll roll) {
		//Golpe a un armor stand (dummy de práctica): siempre acierta, sin tirada de ataque de por medio, así
		//que nunca hay crítico real que anunciar acá.
		CombatFx.hit(target, false);
		CombatFx.diceTick(player);
		ChatFeedback.broadcast(player, ChatFeedback.damageOnly(roll.characterName(), roll.weaponName(), roll.formatted()));
	}

	//Llamado justo después de consumeAdvantage/BardInspirationManager.consumeAttackBonus en cada tirada de
	//ataque: en vez de reenviar la hoja completa, manda solo los dos campos que esos dos métodos acaban de
	//tocar — antes esto pasaba en CADA golpe de CADA combate. Ver AUDIT_TECHNICAL.md M-NET-1.
	private static void sendSheetUpdate(Player player) {
		JsonObject patch = new JsonObject();
		patch.addProperty("nextAttackAdvantage", "normal");
		patch.add("bardicInspiration", JsonNull.INSTANCE);
		//El castigo se consume en el mismo golpe (ver PaladinSmiteManager.consumeIfPending), así que se apaga
		//en el mismo parche en vez de dejar el aviso encendido en el HUD hasta la siguiente tirada.
		patch.add("smitePending", JsonNull.INSTANCE);
		DndsheetsMod.sendSheetFieldUpdate((ServerPlayer) player, patch);
	}

	//Suma el bono configurado (dndsheets-common.toml) por cada nivel de cada encantamiento real que lleve el arma.
	private static int enchantmentBonusFor(ItemStack weapon) {
		int total = 0;
		for (Map.Entry<Enchantment, Integer> entry : EnchantmentHelper.getEnchantments(weapon).entrySet()) {
			String enchantId = ForgeRegistries.ENCHANTMENTS.getKey(entry.getKey()).toString();
			Integer perLevel = Config.enchantBonusPerLevelFor(enchantId);
			if (perLevel != null) total += perLevel * entry.getValue();
		}
		return total;
	}

	//Prefers whatever the player has set for that weapon in their own "attacks" list (so editing it there also changes the auto-roll), falling back to the config default.
	private static String findWeaponExpression(Player player, JsonObject sheet, IdentifiedWeapon weapon, int level, boolean offhandEmpty) {
		String itemId = weapon.id();
		if (UNARMED_ID.equals(itemId)) {
			TraitRegistry.UnarmedProfile profile = unarmedProfileFor(player, sheet, level);
			return profile == null ? null : profile.dice() + " + $" + profile.ability();
		}

		if (sheet.has("attacks")) {
			JsonArray attacks = sheet.getAsJsonArray("attacks");
			for (int i = 0; i < attacks.size(); i++) {
				JsonObject form = attacks.get(i).getAsJsonObject();
				if (!form.has("itemId") || !form.get("itemId").getAsString().equals(itemId)) continue;

				JsonArray rollSet = form.getAsJsonArray("rolls");
				if (rollSet.isEmpty()) continue;
				JsonArray rollGroup = rollSet.get(0).getAsJsonArray();
				if (rollGroup.isEmpty()) continue;
				return rollGroup.get(0).getAsJsonObject().get("expression").getAsString();
			}
		}

		Config.WeaponDefault weaponDefault = Config.weaponDefaultFor(itemId);
		if (weaponDefault == null) weaponDefault = weapon.autoDetected(); //Compatibilidad con armas de otros mods, ver Config.autoDetectWeapon.
		if (weaponDefault == null) return null;
		//Versátil (p.ej. espada larga 1d8/1d10): el dado grande solo cuenta con la otra mano libre de
		//verdad — un escudo o cualquier otro ítem en ella cuenta como "no libre", igual que en 5e.
		String dice = weaponDefault.isVersatile() && offhandEmpty ? weaponDefault.versatileDice() : weaponDefault.dice();
		return dice + " + $" + weaponDefault.ability();
	}
}
