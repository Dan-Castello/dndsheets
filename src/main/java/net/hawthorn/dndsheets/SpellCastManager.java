package net.hawthorn.dndsheets;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Resuelve una petición de {@code SpellCastMessage}: apunta al objetivo que el lanzador está mirando
 * (mismo raycast que usa Minecraft para las flechas) y resuelve el hechizo con la misma mecánica de
 * ataque-vs-CA o salvación-vs-CD que ya usan las armas y los monstruos, gastando un espacio de conjuro.</p>
 */
@Mod.EventBusSubscriber
public class SpellCastManager {
	private static final double RANGE = 30.0;
	private static final Map<String, String> ABILITY_SHEET_KEY = Map.of(
		"str", "strength", "dex", "dexterity", "con", "constitution",
		"int", "intelligence", "wis", "wisdom", "cha", "charisma"
	);

	//ponytail: algunas formas de lanzar (p.ej. la vara apuntando a una entidad) hacen que Minecraft
	//dispare más de un evento de interacción para el mismo clic, duplicando la petición de lanzado.
	//En vez de perseguir el evento exacto que se repite, se ignora una segunda petición del mismo
	//jugador dentro del mismo tick del servidor.
	private static final Map<UUID, Long> lastCastTick = new HashMap<>();

	//A diferencia de furia/segundo aliento/etc., esta entrada no tiene su propio temporizador de
	//expiración (solo sirve para deduplicar dentro del mismo tick), así que sin esto se queda para
	//siempre en el mapa si el jugador no vuelve a conectarse.
	@SubscribeEvent
	public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		lastCastTick.remove(event.getEntity().getUUID());
	}

	private static boolean isAoe(SpellRegistry.Spell spell) {
		return "save".equals(spell.mode()) && spell.aoeRadius() > 0;
	}

	//Agachado + clic con un báculo de área (Bola de Fuego y similares): muestra dónde caería el radio real
	//SIN lanzar el hechizo (no gasta espacio de conjuro ni acción de turno) — mismo anillo de partículas que
	//aoeRing() ya dibuja al impactar de verdad, solo que aquí es un vistazo antes de comprometerse al clic
	//normal. Ver CombatFx.aoeRing para por qué es un anillo en el punto de impacto y no una previsualización
	//de apuntado en 3D.
	public static void previewAoe(ServerPlayer caster, String spellId) {
		SpellRegistry.Spell spell = SpellRegistry.get(spellId);
		if (spell == null || !isAoe(spell)) return;
		CombatFx.aoeRing(caster.level(), findImpactPoint(caster), spell.aoeRadius());
	}

	public static void handleCastRequest(ServerPlayer caster, String spellId) {
		long now = caster.level().getGameTime();
		Long last = lastCastTick.put(caster.getUUID(), now);
		if (last != null && last == now) return;

		SpellRegistry.Spell spell = SpellRegistry.get(spellId);
		if (spell == null) return;

		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		if (casterSheet == null) return;

		int slotsCurrent = casterSheet.has("spellSlotsCurrent") ? casterSheet.get("spellSlotsCurrent").getAsInt() : 0;
		if (slotsCurrent <= 0) {
			caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.no_slots").withStyle(ChatFormatting.RED));
			return;
		}

		//Bola de Fuego y similares (mode:"save" + aoeRadius>0): no hace falta estar mirando directamente a
		//una entidad, el punto de impacto puede ser terreno vacío y golpea a todo lo que esté en el radio.
		boolean isAoe = isAoe(spell);
		Entity target = null;
		List<Entity> aoeTargets = null;
		Vec3 impactPoint = null;

		if (isAoe) {
			impactPoint = findImpactPoint(caster);
			aoeTargets = findAoeTargets(caster, impactPoint, spell.aoeRadius());
			if (aoeTargets.isEmpty()) {
				caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.no_aoe_targets").withStyle(ChatFormatting.GRAY));
				return;
			}
		} else {
			target = findTarget(caster);
			if (target == null) {
				//Un hechizo de curación sin nadie a la vista se lanza sobre uno mismo (Curar Heridas sobre
				//el propio lanzador es el caso más común); el resto de modos sí necesita apuntar a alguien.
				if ("heal".equals(spell.mode())) {
					target = caster;
				} else {
					caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.no_target").withStyle(ChatFormatting.GRAY));
					return;
				}
			}
		}

		//Antes lanzar un hechizo de ataque/salvación contra un monstruo NUNCA arrancaba el modo turnos solo
		//(a diferencia de un golpe con arma, ver CombatManager.autoStartCombatIfNeeded) — tryAct de abajo
		//deja pasar cualquier cosa mientras no haya combate activo, así que el hechizo se resolvía "gratis",
		//sin turno ni congelamiento para nadie. Curar no cuenta: sanar a alguien no es una agresión.
		if (!"heal".equals(spell.mode())) {
			CombatManager.autoStartCombatIfNeeded(isAoe ? aoeTargets.get(0) : target, caster);
		}

		//El turno se comprueba al final, ya con todo validado (hay objetivo, hay espacios): así, si se
		//rechaza por turno, no se cobró ningún recurso por una acción que ni siquiera se intentó de verdad.
		if (!TurnManager.tryAct(caster)) {
			TurnManager.notifyCantAct(caster);
			return;
		}

		String casterName = SheetLoader.characterNameOf(casterSheet, caster);

		//Contrahechizo: se comprueba antes de resolver nada. El espacio del lanzador original se gasta
		//igual (en 5e de verdad el hechizo se considera "usado" aunque lo anulen), pero no hay efecto ni
		//concentración ni segundo objetivo gemelado.
		String counterer = CounterspellManager.findCounterer(caster.level(), caster.position(), caster);
		if (counterer != null) {
			casterSheet.addProperty("spellSlotsCurrent", slotsCurrent - 1);
			sendSlotsUpdate(caster, slotsCurrent - 1);
			ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.counterspelled", casterName, spell.name(), counterer).withStyle(ChatFormatting.DARK_PURPLE));
			return;
		}

		int proficiency = casterSheet.has("proficiencyBonus") ? safeInt(casterSheet.get("proficiencyBonus").getAsString()) : 2;
		int abilityMod = CombatManager.abilityModifier(casterSheet, ABILITY_SHEET_KEY.getOrDefault(spell.castingAbility(), "intelligence"));

		CombatFx.spellCast(caster);
		casterSheet.addProperty("spellSlotsCurrent", slotsCurrent - 1);
		sendSlotsUpdate(caster, slotsCurrent - 1);

		if (spell.concentration()) ConcentrationManager.startConcentrating(caster, spell.name());

		if (isAoe) {
			//Antes no había ninguna representación visual del radio: te enterabas de a quién golpeó leyendo
			//el chat, después del hecho — un anillo de partículas en el radio real usado deja ver el alcance
			//de la explosión, no solo el punto de impacto (ver CombatFx.aoeRing).
			CombatFx.aoeRing(caster.level(), impactPoint, spell.aoeRadius());
			//Gemelar un hechizo que ya reparte daño a todo un radio no tendría sentido (5e tampoco lo deja);
			//se ignora el flag pendiente en vez de consumirlo, para no gastarlo en un lanzado que no aplica.
			for (Entity aoeTarget : aoeTargets) castSaveSpell(caster, casterName, spell, aoeTarget, proficiency, abilityMod);
		} else if ("save".equals(spell.mode())) {
			castSaveSpell(caster, casterName, spell, target, proficiency, abilityMod);
			castTwinnedIfPending(caster, casterName, spell, target, proficiency, abilityMod);
		} else if ("heal".equals(spell.mode())) {
			castHealSpell(caster, casterName, spell, target);
			castTwinnedIfPending(caster, casterName, spell, target, proficiency, abilityMod);
		} else {
			castAttackSpell(caster, casterName, spell, target, proficiency, abilityMod);
			castTwinnedIfPending(caster, casterName, spell, target, proficiency, abilityMod);
		}
	}

	//Metamagia: Hechizo Gemelo (ver SorcererMetamagicManager) — si el hechicero lo activó antes de lanzar,
	//el MISMO hechizo se resuelve otra vez contra un segundo objetivo válido cercano, sin gastar un
	//espacio de conjuro extra (el coste real en 5e son puntos de hechicero, que este mod no modela).
	private static void castTwinnedIfPending(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Entity firstTarget, int proficiency, int abilityMod) {
		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		if (!SorcererMetamagicManager.consumePending(casterSheet)) return;

		Entity secondTarget = findNearestOther(caster, firstTarget);
		if (secondTarget == null) {
			caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.twin_no_target").withStyle(ChatFormatting.GRAY));
			return;
		}

		if ("save".equals(spell.mode())) {
			castSaveSpell(caster, casterName, spell, secondTarget, proficiency, abilityMod);
		} else if ("heal".equals(spell.mode())) {
			castHealSpell(caster, casterName, spell, secondTarget);
		} else {
			castAttackSpell(caster, casterName, spell, secondTarget, proficiency, abilityMod);
		}
	}

	//Mismo criterio de objetivo válido que findAoeTargets (jugador o monstruo invocado, vivo), pero por
	//cercanía al lanzador en vez de raycast — un segundo objetivo no tiene por qué estar en la mira.
	private static Entity findNearestOther(ServerPlayer caster, Entity excluding) {
		AABB box = new AABB(caster.position(), caster.position()).inflate(RANGE);
		Entity best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (Entity candidate : caster.level().getEntities((Entity) null, box,
				e -> e != caster && e != excluding && e.isAlive() && (e instanceof Player || TurnManager.isMonster(e)))) {
			double distSq = candidate.position().distanceToSqr(caster.position());
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = candidate;
			}
		}
		return best;
	}

	//Sin tirada de ataque ni salvación: el objetivo (uno mismo si no había nadie a la vista, ver arriba)
	//recupera PG de verdad, igual de "real" que el daño de un ataque o un hechizo de ataque/salvación. A
	//diferencia del daño (dado puro, sin característica), curar SÍ suma la característica de lanzamiento
	//en 5e de verdad (p.ej. "1d8 + $wis" para Curar Heridas) — por eso se tira con la hoja del LANZADOR,
	//no con una vacía como hace el daño de ataque/salvación.
	private static void castHealSpell(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Entity target) {
		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		DiceManager.RollOutcome healRoll = DiceManager.roll(casterSheet != null ? casterSheet : new JsonObject(), spell.dice());
		if (healRoll.result() == null) return;

		int amount = healRoll.result().getValue();
		healTarget(target, amount);
		CombatFx.heal(target);
		ChatFeedback.broadcast(caster, ChatFeedback.healResult(casterName, nameOf(target), spell.name(), healRoll.formatted()));
	}

	private static void healTarget(Entity target, int amount) {
		if (target instanceof ServerPlayer player) {
			player.heal(amount);
			return;
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(target);
		if (block == null) {
			//Mob de compatibilidad (Enemy de otro mod o vanilla, sin bloque de estadísticas propio, ver
			//TurnManager.isMonster): a diferencia de un monstruo propio (PG trackeado aparte en NBT), su PG
			//real ES su salud vanilla de Minecraft — antes esto no hacía nada (block==null, sin más), así
			//que curar un mob de compatibilidad no tenía ningún efecto.
			if (target instanceof LivingEntity living) living.heal(amount);
			return;
		}
		int newHp = Math.min(block.maxHp(), MonsterRegistry.currentHpOf(target) + amount);
		MonsterRegistry.setCurrentHp(target, newHp);
	}

	//Mismo raycast que findTarget pero incluyendo bloques: si no hay ninguna entidad en la mira, el punto
	//de impacto es donde el rayo choca con el terreno (o el final del rango si no choca con nada).
	private static Vec3 findImpactPoint(ServerPlayer caster) {
		Entity direct = findTarget(caster);
		if (direct != null) return direct.position();

		Vec3 eyePos = caster.getEyePosition(1.0f);
		Vec3 endPos = eyePos.add(caster.getViewVector(1.0f).scale(RANGE));
		BlockHitResult blockHit = caster.level().clip(new ClipContext(eyePos, endPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
		return blockHit.getLocation();
	}

	//Radio esférico como antes de a quién puede llegar la explosión, pero ahora con oclusión de terreno
	//real: un objetivo dentro del radio solo cuenta si hay línea de visión libre de bloques sólidos desde
	//el punto de impacto hasta él (una pared lo protege, igual que en 5e de verdad).
	private static List<Entity> findAoeTargets(ServerPlayer caster, Vec3 center, double radius) {
		AABB box = new AABB(center, center).inflate(radius);
		List<Entity> candidates = caster.level().getEntities((Entity) null, box,
			entity -> entity != caster && entity.isAlive() && (entity instanceof Player || TurnManager.isMonster(entity))
				&& entity.position().distanceTo(center) <= radius);

		List<Entity> visible = new ArrayList<>();
		for (Entity entity : candidates) {
			if (hasClearPath(caster, center, entity.getBoundingBox().getCenter())) visible.add(entity);
		}
		return visible;
	}

	//ponytail: un solo rayo al centro de la hitbox del objetivo, no varios puntos de su volumen ni un
	//cálculo de cobertura parcial — alcanza para "un muro entero bloquea, un hueco en la pared no", que es
	//lo que pedía AUDIT.md; una esquina asomando por el borde de una pared puede dar un falso negativo.
	private static boolean hasClearPath(ServerPlayer caster, Vec3 from, Vec3 to) {
		BlockHitResult hit = caster.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
		return hit.getType() == HitResult.Type.MISS;
	}

	//Mismo raycast que usa Minecraft internamente para saber a qué le pegó una flecha, reutilizado para
	//apuntar el hechizo a quien el lanzador tenga delante (jugador o monstruo invocado).
	private static Entity findTarget(ServerPlayer caster) {
		Vec3 eyePos = caster.getEyePosition(1.0f);
		Vec3 viewVec = caster.getViewVector(1.0f);
		Vec3 endPos = eyePos.add(viewVec.scale(RANGE));
		AABB searchBox = caster.getBoundingBox().expandTowards(viewVec.scale(RANGE)).inflate(1.0);

		EntityHitResult hit = ProjectileUtil.getEntityHitResult(caster.level(), caster, eyePos, endPos, searchBox,
			entity -> entity != caster && entity.isAlive() && (entity instanceof Player || TurnManager.isMonster(entity)));
		return hit != null ? hit.getEntity() : null;
	}

	private static void castAttackSpell(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Entity target, int proficiency, int abilityMod) {
		if (TurnManager.isMonster(target)) MonsterRegistry.faceTarget(target, caster);
		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		DiceManager.Advantage advantage = casterSheet != null ? CombatManager.consumeAdvantage(casterSheet) : DiceManager.Advantage.NORMAL;
		int inspiration = BardInspirationManager.consumeAttackBonus(casterSheet);
		DiceManager.AttackRoll attackRoll = DiceManager.rollAttack(new JsonObject(), "1d20 + " + (abilityMod + proficiency + inspiration), advantage);
		if (attackRoll.outcome().result() == null) return;
		if (casterSheet != null) sendAdvantageAndInspirationUpdate(caster);

		int targetAc = armorClassOfEntity(target);
		String targetName = nameOf(target);

		if (attackRoll.criticalMiss() || (!attackRoll.criticalHit() && attackRoll.outcome().result().getValue() < targetAc)) {
			ChatFeedback.broadcast(caster, ChatFeedback.attackResult(casterName, targetName, spell.name(), attackRoll.outcome().formatted(), targetAc, false, null, inspiration));
			return;
		}

		DiceManager.DamageResult damageRoll = DiceManager.rollDamage(new JsonObject(), spell.dice(), attackRoll.criticalHit());
		if (damageRoll.formatted() == null) return;

		applyDamage(target, damageRoll.amount(), spell.damageType());
		CombatFx.hit(target, attackRoll.criticalHit(), spell.damageType());
		ChatFeedback.broadcast(caster, ChatFeedback.attackResult(casterName, targetName, spell.name(), attackRoll.outcome().formatted(), targetAc, true, damageRoll.formatted(), inspiration));
		applySpellEffect(caster, spell, target);
	}

	private static void castSaveSpell(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Entity target, int proficiency, int abilityMod) {
		if (TurnManager.isMonster(target)) MonsterRegistry.faceTarget(target, caster);
		int saveDc = 8 + proficiency + abilityMod;
		String targetName = nameOf(target);

		DiceManager.RollOutcome damageRoll = DiceManager.roll(new JsonObject(), spell.dice());
		if (damageRoll.result() == null) return;

		Combatant.SaveRoll saveRoll = rollTargetSave(target, spell.saveAbility());
		if (saveRoll == null || saveRoll.formatted() == null) return;

		boolean saved = saveRoll.succeeds(saveDc);
		int finalDamage = saved ? (spell.halfOnSave() ? damageRoll.result().getValue() / 2 : 0) : damageRoll.result().getValue();
		Component outcomeLabel = Component.translatable(saved ? (spell.halfOnSave() ? "chat.dndsheets.spell.save_half" : "chat.dndsheets.spell.save_none") : "chat.dndsheets.spell.save_fail");

		CombatFx.spellImpact(target, saved, spell.damageType());
		ChatFeedback.broadcast(caster, ChatFeedback.saveResult(casterName, targetName, spell.name(), saveRoll.formatted(), saveDc, saved, outcomeLabel,
			finalDamage > 0 ? damageRoll.formatted() + " (" + finalDamage + ")" : null));

		if (finalDamage > 0) {
			applyDamage(target, finalDamage, spell.damageType());
			applySpellEffect(caster, spell, target);
		}
	}

	//Mismo patrón que MonsterActionManager.applyEffectFromHit: si el hechizo trae appliesEffect (ver
	//SpellRegistry.Spell) y de verdad conectó (llamado solo cuando ya hubo daño > 0), engancha el efecto
	//de estado al objetivo. Si además era un hechizo de concentración, le suma el objetivo/efecto al
	//registro de ConcentrationManager para que se revierta solo si el lanzador pierde la concentración.
	private static void applySpellEffect(ServerPlayer caster, SpellRegistry.Spell spell, Entity target) {
		if (!spell.appliesEffect()) return;
		TurnManager.applyEffect(target, spell.effectName(), spell.effectDice(), spell.effectTurns(), caster);
		ChatFeedback.broadcast(target, Component.translatable("chat.dndsheets.monster.effect_applied", nameOf(target), spell.effectName(), spell.effectTurns()).withStyle(ChatFormatting.DARK_PURPLE));
		if (spell.concentration()) ConcentrationManager.attachEffect(caster, target.getId(), spell.effectName());
	}

	private static Combatant.SaveRoll rollTargetSave(Entity target, String saveAbility) {
		Combatant combatant = Combatant.of(target);
		if (combatant != null) return combatant.rollSave(saveAbility);
		//Jugador sin hoja cargada: no se resuelve nada, igual que antes — mejor no hacer daño que hacerlo
		//con características inventadas.
		if (target instanceof Player) return null;
		//Mob de otro mod sin bloque de estadísticas (ver TurnManager.isMonster): no hay características que
		//consultar, así que tira el d20 pelado, exactamente como hacía antes con modificador 0.
		return new Combatant.SaveRoll(DiceManager.roll(new JsonObject(), "1d20"), null);
	}

	//Público: también lo usa TurnManager para el daño de efectos de estado (veneno, etc.) al inicio del turno.
	public static void applyDamage(Entity target, int amount) {
		applyDamage(target, amount, null);
	}

	public static void applyDamage(Entity target, int amount, String damageType) {
		if (target instanceof ServerPlayer player) {
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			int applied = DamageTypes.applyMultiplier(amount, DamageTypes.multiplierFor(player, sheet, damageType));
			player.hurt(player.damageSources().generic(), applied);
			ConcentrationManager.onDamageTaken(player, applied);
			return;
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(target);
		if (block == null) {
			//Mob de compatibilidad (Enemy de otro mod o vanilla, ver TurnManager.isMonster): a diferencia de
			//un monstruo propio (PG trackeado aparte en NBT, salud vanilla nunca se mueve, por eso el
			//die()/setHealth(0) a mano de abajo), su PG real ES su salud vanilla — hurt() ya dispara solo el
			//camino de muerte vanilla real (loot, XP, sonido) si lo mata, sin nada manual. Antes esto no
			//hacía nada (block==null, sin más): el mensaje de impacto se mostraba pero el mob no recibía daño.
			if (target instanceof LivingEntity living) living.hurt(target.damageSources().generic(), amount);
			return;
		}
		int remainingHp = MonsterRegistry.currentHpOf(target) - amount;
		if (remainingHp <= 0) {
			TurnManager.markDefeated(target.getId());
			//die(), no remove(): un remove() a secas nunca pasa por el camino de muerte vanilla (loot table,
			//XP, sonido/partículas de muerte), así que un monstruo "asesinado" así jamás soltaba nada. Nuestra
			//salud real de Minecraft nunca baja (el HP de 5e se trackea aparte en MonsterRegistry), así que
			//die() no puede inferir la muerte solo — hay que llamarlo a mano en cuanto NUESTRO HP llega a 0.
			//setHealth(0) es imprescindible ANTES de die(): sin ella isDeadOrDying() sigue viendo salud llena
			//y nunca arranca el tickDeath() que de verdad elimina la entidad — se queda tirado para siempre
			//(ver CombatManager.resolveAttackOnMonster, mismo bug con el mismo arreglo).
			if (target instanceof LivingEntity living) {
				living.setHealth(0.0F);
				living.die(target.damageSources().generic());
			} else {
				target.remove(Entity.RemovalReason.KILLED);
			}
		} else {
			MonsterRegistry.setCurrentHp(target, remainingHp);
		}
	}

	private static int armorClassOfEntity(Entity target) {
		if (target instanceof Player player) {
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			return sheet != null ? CombatManager.armorClassOf(player, sheet) : 10;
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(target);
		return block != null ? block.ac() : 10;
	}

	private static String nameOf(Entity target) {
		if (target instanceof Player player) {
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			return SheetLoader.characterNameOf(sheet, player);
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(target);
		return block != null ? block.name() : target.getName().getString();
	}

	private static int safeInt(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 2;
		}
	}

	//Antes reenviaban la hoja completa en cada hechizo lanzado — ahora solo el campo que de verdad cambió.
	//Ver AUDIT_TECHNICAL.md M-NET-1.
	private static void sendSlotsUpdate(ServerPlayer player, int slotsCurrent) {
		JsonObject patch = new JsonObject();
		patch.addProperty("spellSlotsCurrent", slotsCurrent);
		DndsheetsMod.sendSheetFieldUpdate(player, patch);
	}

	//Llamado justo después de CombatManager.consumeAdvantage/BardInspirationManager.consumeAttackBonus:
	//manda solo los dos campos que esos dos métodos acaban de tocar, mismo patrón que CombatManager.
	private static void sendAdvantageAndInspirationUpdate(ServerPlayer player) {
		JsonObject patch = new JsonObject();
		patch.addProperty("nextAttackAdvantage", "normal");
		patch.add("bardicInspiration", JsonNull.INSTANCE);
		DndsheetsMod.sendSheetFieldUpdate(player, patch);
	}
}
