package net.hawthorn.dndsheets;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
		//Una zona persistente tiene aoeRadius pero NO se resuelve como area al lanzarla: se coloca (ver ZoneManager).
		return "save".equals(spell.mode()) && spell.aoeRadius() > 0 && !spell.isZone();
	}

	//Agachado + clic con un báculo de área o de zona: enseña dónde caería SIN lanzar el hechizo (no gasta
	//espacio de conjuro ni acción de turno), antes de comprometerse al clic normal.
	public static void previewAoe(ServerPlayer caster, String spellId) {
		SpellRegistry.Spell spell = SpellRegistry.get(spellId);
		if (spell == null) return;
		//Una zona es la que MÁS falta hace previsualizar y era la única que no lo hacía: isAoe() la excluye
		//a propósito (no se resuelve al lanzarla, se coloca), así que el clic agachado no pintaba nada y el
		//muro se colocaba a ciegas — para diez asaltos y con el espacio ya gastado, sin poder recolocarlo.
		if (spell.isZone()) {
			ZoneManager.preview(caster, spell, spell.followsCaster() ? null : findImpactPoint(caster));
			return;
		}
		if (!isAoe(spell)) return;
		//Cada forma se previsualiza con SU geometría. Antes esto se rendía con el cono y la línea (un anillo
		//en el punto de impacto mentiría sobre a quién alcanzan), así que las dos formas donde MÁS falta hace
		//ver el área —el grupo propio está justo detrás— eran precisamente las únicas sin previsualización.
		if (spell.originatesAtCaster()) CombatFx.shapeOutline(caster, spell.aoeShape(), spell.aoeRadius(), spell.damageType());
		else CombatFx.aoeRing(caster.level(), findImpactPoint(caster), spell.aoeRadius());
	}

	/**
	 * <p>Gasta el espacio y avisa al cliente. Sin nivel pedido coge el más bajo que sirva (quemar uno alto
	 * pudiendo usar uno bajo es tirar el recurso caro); con {@code minSlotLevel} respeta la elección del
	 * jugador de subir el conjuro de nivel. Devuelve el nivel realmente gastado.</p>
	 */
	private static int spendSlot(ServerPlayer caster, JsonObject casterSheet, int spellLevel, int minSlotLevel) {
		int spent = SpellSlots.spend(casterSheet, spellLevel, minSlotLevel);
		//Decirlo, no solo hacerlo. El espacio se descontaba en silencio: el único rastro era un número del
		//HUD que baja, y con un truco (que por regla NO gasta nada) la conclusión razonable desde fuera es
		//"esto ignora los espacios de conjuro". Nombrar el nivel gastado y lo que queda DE ESE NIVEL es lo
		//que convierte el recurso en algo que se siente. Solo a quien lanza: es su contabilidad.
		if (spent > 0) {
			caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.slot_spent",
				spent, SpellSlots.currentSlots(casterSheet)[spent]).withStyle(ChatFormatting.DARK_AQUA));
		}
		//Persistir, no solo avisar: el espacio gastado es estado de la hoja (invariante 4). Antes solo salía
		//el parche al cliente, así que cerrar el servidor antes del autosave devolvía el espacio ya gastado.
		//Se guarda con saveServer y no con saveAndSync porque el parche por campo de abajo ya sincroniza, y
		//es mucho más barato que mandar la hoja entera en cada lanzamiento.
		SheetLoader.saveServer(casterSheet, caster.getStringUUID());
		sendSlotsUpdate(caster, casterSheet);
		return spent;
	}

	/** Lanzado sin elegir nivel: el báculo de lanzado rápido y cualquier otra vía que no pregunte. */
	public static void handleCastRequest(ServerPlayer caster, String spellId) {
		handleCastRequest(caster, spellId, 0);
	}

	/**
	 * <p>Un lanzamiento son dos mitades: {@link #prepare} valida y <b>cobra</b> (objetivo, espacio de
	 * conjuro, turno, contrahechizo), y {@link #resolve} aplica el efecto. Estaban fundidas en un solo
	 * método de ~170 líneas hasta que el tiempo de lanzamiento (ver {@link CastingManager}) obligó a que
	 * pasara tiempo real entre las dos. El corte no es estético: es el que permite que un conjuro que
	 * tarda en salir se pueda interrumpir DESPUÉS de haber costado su acción y su espacio, que es
	 * exactamente lo que pasa en la mesa.</p>
	 *
	 * @param slotLevel nivel de espacio elegido por el jugador, o 0 para el más bajo que sirva.
	 */
	public static void handleCastRequest(ServerPlayer caster, String spellId, int slotLevel) {
		CastRequest request = prepare(caster, spellId, slotLevel);
		if (request == null) return;

		//castTicks 0 (el valor por defecto, y el de todo pack escrito antes de que el campo existiera)
		//resuelve aquí mismo, en el mismo tick: exactamente el comportamiento de siempre.
		int castTicks = request.spell().castTicksAt(Config.castTicksPerLevel(), Config.castTicksMax());
		if (castTicks <= 0) {
			resolve(caster, request);
			return;
		}
		CastingManager.begin(caster, request, castTicks);
	}

	/**
	 * <p>Lo que hace falta para resolver un conjuro cuyo coste YA se pagó. Viaja entero por
	 * {@link CastingManager} mientras dura el lanzamiento, así que el objetivo y el punto de impacto son
	 * los de cuando se apuntó — apuntar otra vez al resolver dejaría que un conjuro corrigiera su puntería
	 * solo mientras el lanzador gira la cámara.</p>
	 */
	record CastRequest(SpellRegistry.Spell spell, Entity target, List<Entity> aoeTargets, Vec3 impactPoint,
		boolean isAoe, int proficiency, int abilityMod, String casterName) {}

	/** @return null si el lanzamiento se rechaza; en ese caso no se ha cobrado nada que no diga el comentario. */
	private static CastRequest prepare(ServerPlayer caster, String spellId, int slotLevel) {
		long now = caster.level().getGameTime();
		Long last = lastCastTick.put(caster.getUUID(), now);
		if (last != null && last == now) return null;

		SpellRegistry.Spell spell = SpellRegistry.get(spellId);
		if (spell == null) return null;

		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		if (casterSheet == null) return null;

		//Los trucos (nivel 0) son a voluntad en 5e: ni piden espacio ni lo gastan. Spell.level() existía
		//desde el principio y aquí no se miraba, así que un truco consumía espacio como cualquier otro Y
		//quedaba bloqueado al quedarse a cero — o sea que al lanzador se le acababa su ataque básico, que
		//es justo lo que un truco NO puede hacer. Se calcula una vez y se usa en los tres sitios que
		//tocaban el contador.
		boolean needsSlot = spell.level() > 0;

		//Un truco no se puede subir de nivel (no gasta espacio ninguno), así que la elección se ignora en
		//vez de convertirse en un gasto que la regla no permite.
		int requestedLevel = needsSlot ? Math.max(spell.level(), Math.min(slotLevel, SpellSlots.MAX_SPELL_LEVEL)) : 0;

		//Sin preparar no se lanza, y se comprueba lo primero de todo: no cuesta espacio, ni acción, ni
		//siquiera buscar objetivo. Mismo criterio que el objetivo del tipo equivocado más abajo — castigar
		//con un recurso por una regla que el mod conoce y el jugador no puede ver sería el peor final
		//posible. Tres cosas pasan siempre: un truco (no se prepara), una hoja sin el campo (invariante 8) y
		//un conjuro que la hoja NO conoce — ese último es el báculo, que lanza sin haber aprendido nada.
		if (needsSlot && !SpellRegistry.preparationAllows(casterSheet, spellId)) {
			caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.not_prepared", ContentNames.of(spell.name()))
				.withStyle(ChatFormatting.GRAY));
			return null;
		}

		//Se comprueba aquí sin gastar, y se gasta más abajo: si el hechizo se rechaza por falta de objetivo
		//o porque no es tu turno, no se puede haber cobrado ya el espacio.
		if (!SpellSlots.hasSlotFor(casterSheet, requestedLevel)) {
			//El nivel, no "no te quedan espacios" a secas: con 4 espacios de nivel 1 y ninguno de nivel 2, el
			//mensaje viejo se contradecía con el HUD, que enseñaba 4 disponibles. Lo que falta es de NIVEL 2
			//o superior, y decirlo es la diferencia entre una regla y un fallo aparente.
			caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.no_slots_of_level", requestedLevel)
				.withStyle(ChatFormatting.RED));
			return null;
		}

		//Bola de Fuego y similares (mode:"save" + aoeRadius>0): no hace falta estar mirando directamente a
		//una entidad, el punto de impacto puede ser terreno vacío y golpea a todo lo que esté en el radio.
		boolean isAoe = isAoe(spell);
		Entity target = null;
		List<Entity> aoeTargets = null;
		Vec3 impactPoint = null;

		if (spell.isZone() || spell.isSelfTargeted() || spell.isSummon()) {
			//Sin objetivo, pero una zona SÍ tiene punto: se coloca donde se apunta (ver ZoneManager.place).
			//buff/temphp/summon son sobre uno mismo y no necesitan ni lo uno ni lo otro.
			if (spell.isZone() && !spell.followsCaster()) impactPoint = findImpactPoint(caster);
		} else if (isAoe) {
			impactPoint = findImpactPoint(caster);
			aoeTargets = findAoeTargets(caster, impactPoint, spell.aoeRadius(), spell.aoeShape());
			//En un área, a quien el conjuro no puede afectar simplemente se le cae de la lista: la explosión
			//pasa por encima de él. No se rechaza el lanzado, que es lo que sí pasa con un objetivo único.
			//Copia local porque "spell" se reasigna más abajo al subirlo de nivel, y una lambda no puede
			//capturar una variable que cambia.
			SpellRegistry.Spell cast = spell;
			aoeTargets.removeIf(entity -> !cast.affects(MonsterRegistry.typeOf(entity)));
			//Un área vacía se lanza igual. Rechazarla obligaba a tener a alguien dentro del radio para poder
			//tirar una Bola de Fuego, o sea que no se podía prender un bosque, abrir un boquete ni cubrir una
			//retirada — cosas que en la mesa se hacen constantemente. El espacio se gasta y el mundo reacciona
			//(ver SurfaceManager al resolver); simplemente no hay a quién dañar.
		} else {
			target = findTarget(caster);
			if (target == null) {
				//Un hechizo de curación sin nadie a la vista se lanza sobre uno mismo (Curar Heridas sobre
				//el propio lanzador es el caso más común).
				//Todo lo demás se lanza AL TERRENO O AL AIRE en vez de rechazarse. Exigir una criatura en la
				//mira convertía el hechizo en un arma teledirigida: no se podía disparar de aviso, prender una
				//puerta, iluminar una sala ni fallar a propósito, y apuntar al suelo daba el mismo "no hay
				//objetivo" que no apuntar a nada. En la mesa apuntar es libre y el hechizo sale igual.
				if ("heal".equals(spell.mode())) target = caster;
				else impactPoint = findImpactPoint(caster);
			}
			//Objetivo del tipo equivocado: se avisa y NO se cobra el espacio. Cobrarlo castigaría por una
			//regla que el mod conoce y el jugador no puede ver: en la mesa, el DM diría "eso no es un
			//humanoide" antes de que gastes nada.
			CreatureType targetType = target != null ? MonsterRegistry.typeOf(target) : null;
			if (targetType != null && !spell.affects(targetType)) {
				caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.wrong_target_type",
					ContentNames.of(spell.name()), nameOf(target), targetType.label()).withStyle(ChatFormatting.GRAY));
				return null;
			}
		}

		//Antes lanzar un hechizo de ataque/salvación contra un monstruo NUNCA arrancaba el modo turnos solo
		//(a diferencia de un golpe con arma, ver CombatManager.autoStartCombatIfNeeded) — tryAct de abajo
		//deja pasar cualquier cosa mientras no haya combate activo, así que el hechizo se resolvía "gratis",
		//sin turno ni congelamiento para nadie. Curar no cuenta: sanar a alguien no es una agresión.
		//Un lanzado que no alcanza a nadie no agrede a nadie, así que tampoco arranca el modo turnos: no hay
		//a quién meter en la iniciativa. Son tres casos —un muro (que lo arrancará el primero que empiece su
		//turno dentro), un área vacía y un hechizo al aire o al terreno— y este es además el guardia que
		//evita desreferenciar null en los tres.
		Entity aggressed = isAoe ? (aoeTargets.isEmpty() ? null : aoeTargets.get(0)) : target;
		if (aggressed != null && !"heal".equals(spell.mode()) && !spell.isZone() && !spell.isSelfTargeted() && !spell.isSummon()) {
			CombatManager.autoStartCombatIfNeeded(aggressed, caster);
		}

		//El turno se comprueba al final, ya con todo validado (hay objetivo, hay espacios): así, si se
		//rechaza por turno, no se cobró ningún recurso por una acción que ni siquiera se intentó de verdad.
		if (!TurnManager.tryAct(caster)) {
			TurnManager.notifyCantAct(caster);
			return null;
		}

		String casterName = SheetLoader.characterNameOf(casterSheet, caster);

		//Contrahechizo: se comprueba antes de resolver nada. El espacio del lanzador original se gasta
		//igual (en 5e de verdad el hechizo se considera "usado" aunque lo anulen), pero no hay efecto ni
		//concentración ni segundo objetivo gemelado.
		String counterer = CounterspellManager.findCounterer(caster.level(), caster.position(), caster);
		if (counterer != null) {
			//El espacio se gasta igual aunque lo anulen (en 5e el hechizo se considera usado), pero un truco
			//no tiene espacio que gastar. Se gasta el que se pidió: contrarrestarlo no te devuelve el de 5º.
			if (needsSlot) spendSlot(caster, casterSheet, spell.level(), requestedLevel);
			ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.counterspelled", casterName, ContentNames.of(spell.name()), counterer).withStyle(ChatFormatting.DARK_PURPLE));
			return null;
		}

		int proficiency = casterSheet.has("proficiencyBonus") ? safeInt(casterSheet.get("proficiencyBonus").getAsString()) : 2;
		int abilityMod = CombatManager.abilityModifier(casterSheet, ABILITY_SHEET_KEY.getOrDefault(spell.castingAbility(), "intelligence"));

		//Un truco es a voluntad y no gasta espacio: se dice explícitamente porque el silencio se lee como
		//que el mod no lleva la cuenta. Es la otra mitad del mensaje de spendSlot.
		if (!needsSlot) {
			caster.sendSystemMessage(Component.translatable("chat.dndsheets.spell.cantrip_free", ContentNames.of(spell.name()))
				.withStyle(ChatFormatting.DARK_AQUA));
		}
		//El nivel del espacio no se sabe hasta gastarlo: se pidió uno de 3º, pero si estaban agotados salió
		//por uno de 4º y el conjuro sube con él. De ahí que la subida de nivel se aplique DESPUÉS de gastar
		//y no antes, con el nivel real y no con el pedido.
		if (needsSlot) spell = spell.upcastTo(spendSlot(caster, casterSheet, spell.level(), requestedLevel));
		//Y lo que no se puede subir gastando un espacio sube con quien lanza: un truco de daño gana un dado a
		//los niveles 5, 11 y 17. No hace falta un if — para todo lo demás devuelve el mismo conjuro.
		spell = spell.atCasterLevel(SheetLoader.characterLevelOf(casterSheet, caster));

		return new CastRequest(spell, target, aoeTargets, impactPoint, isAoe, proficiency, abilityMod, casterName);
	}

	/**
	 * <p>Aplica el efecto de un conjuro ya pagado. Corre en el mismo tick que {@link #prepare} cuando el
	 * conjuro es instantáneo, y {@code castTicks} después cuando no lo es.</p>
	 */
	static void resolve(ServerPlayer caster, CastRequest request) {
		SpellRegistry.Spell spell = request.spell();
		Entity target = request.target();
		List<Entity> aoeTargets = request.aoeTargets();
		Vec3 impactPoint = request.impactPoint();
		boolean isAoe = request.isAoe();
		int proficiency = request.proficiency();
		int abilityMod = request.abilityMod();
		String casterName = request.casterName();

		//Se relee en vez de viajar dentro del CastRequest: entre prepare y resolve puede haber pasado un
		//segundo entero, y en ese segundo el jugador puede haber cambiado de personaje (SheetLoader.sheets
		//va indexado por id de personaje, no por jugador). Escribir sobre el JsonObject viejo guardaría los
		//PG temporales o la mejora de arma en la hoja equivocada.
		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		if (casterSheet == null) return;

		//El fogonazo de disparo y el rastro salen al RESOLVER, no al empezar. Estaban en prepare(), o sea
		//ANTES de la carga: con tiempo de lanzamiento el jugador veía estallido → hélice de carga → impacto,
		//la secuencia al revés y sin recompensa al final. Un conjuro instantáneo (castTicks 0) resuelve en
		//este mismo tick, así que para él no cambia nada.
		CombatFx.spellCast(caster, spell.school());
		//Zona/autolanzado/invocación no tienen un punto único al que viajar (ver CombatFx.spellTravel).
		if (target != null) CombatFx.spellTravel(caster, target, spell.damageType());
		else if (impactPoint != null) CombatFx.spellTravel(caster, impactPoint, spell.damageType());

		if (spell.concentration()) ConcentrationManager.startConcentrating(caster, spell.name());

		if (spell.isSummon()) {
			SummonManager.summon(caster, spell, proficiency, abilityMod);
		} else if ("buff".equals(spell.mode())) {
			//Se concede al propio lanzador: es un hechizo sobre uno mismo, no necesita objetivo delante.
			WeaponBuffManager.grant(casterSheet, spell.name(), spell.dice(), spell.damageType(), ZoneManager.DEFAULT_ROUNDS);
			//grant() solo toca el JsonObject, y el guardado de spendSlot ya paso antes que esto.
			SheetLoader.saveServer(casterSheet, caster.getStringUUID());
			ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.buff_granted",
				casterName, ContentNames.of(spell.name()), spell.dice()).withStyle(ChatFormatting.GOLD));
		} else if ("temphp".equals(spell.mode())) {
			Combatant self = Combatant.of(caster);
			DiceManager.RollOutcome roll = DiceManager.roll(casterSheet, spell.dice());
			if (self != null && roll.result() != null) {
				self.grantTemporaryHp(roll.result().getValue());
				ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.temp_hp_granted",
					casterName, ContentNames.of(spell.name()), roll.result().getValue()).withStyle(ChatFormatting.GOLD));
			}
		} else if (spell.isZone()) {
			ZoneManager.place(caster, spell, 8 + proficiency + abilityMod, impactPoint);
		} else if (isAoe) {
			//Antes no había ninguna representación visual del radio: te enterabas de a quién golpeó leyendo
			//el chat, después del hecho — un anillo de partículas en el radio real usado deja ver el alcance
			//de la explosión, no solo el punto de impacto (ver CombatFx.aoeRing).
			//Esfera: anillo en el punto de impacto. Cono y línea: su contorno real desde el lanzador, que
			//hasta ahora no pintaba nada (ver CombatFx.shapeOutline).
			if (spell.originatesAtCaster()) CombatFx.shapeOutline(caster, spell.aoeShape(), spell.aoeRadius(), spell.damageType());
			else CombatFx.aoeRing(caster.level(), impactPoint, spell.aoeRadius());
			//Gemelar un hechizo que ya reparte daño a todo un radio no tendría sentido (5e tampoco lo deja);
			//se ignora el flag pendiente en vez de consumirlo, para no gastarlo en un lanzado que no aplica.
			for (Entity aoeTarget : aoeTargets) castSaveSpell(caster, casterName, spell, aoeTarget, proficiency, abilityMod);
			//Superficies (ver SurfaceManager): fuego que deja el suelo ardiendo, agua real que lo apaga o
			//conduce un rayo. Después de resolver el golpe, no antes — necesita saber a quién ya alcanzó de
			//lleno para no cobrarle el chispazo de agua+rayo dos veces al mismo objetivo.
			if (caster.level() instanceof ServerLevel surfaceLevel && !spell.originatesAtCaster()) {
				SurfaceManager.onAoeImpact(surfaceLevel, impactPoint, spell.damageType(), aoeTargets);
			}
		} else if (target == null) {
			castAtPoint(caster, casterName, spell, impactPoint);
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

	/**
	 * <p>El hechizo salió y no alcanzó a nadie: no hay tirada de ataque ni salvación que resolver, pero
	 * <b>sí</b> pasó — el espacio ya está gastado y el mundo reacciona igual que bajo un área. El fuego
	 * prende el suelo, el rayo electrifica el agua ({@link SurfaceManager}), que es lo que convierte
	 * "fallar" en una jugada y no en un mensaje de error.</p>
	 */
	private static void castAtPoint(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Vec3 impactPoint) {
		if (impactPoint == null) return;
		//Radio 1: el anillo marca dónde cayó, sin mentir sobre un área de efecto que este hechizo no tiene.
		CombatFx.aoeRing(caster.level(), impactPoint, 1.0);
		ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.hits_ground",
			casterName, ContentNames.of(spell.name())).withStyle(ChatFormatting.GRAY));
		if (caster.level() instanceof ServerLevel level) {
			SurfaceManager.onAoeImpact(level, impactPoint, spell.damageType(), List.of());
		}
	}

	//Metamagia: Hechizo Gemelo (ver SorcererMetamagicManager) — si el hechicero lo activó antes de lanzar,
	//el MISMO hechizo se resuelve otra vez contra un segundo objetivo válido cercano, sin gastar un
	//espacio de conjuro extra (el coste real en 5e son puntos de hechicero, que este mod no modela).
	private static void castTwinnedIfPending(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Entity firstTarget, int proficiency, int abilityMod) {
		JsonObject casterSheet = SheetLoader.getServerSheet(caster.getStringUUID());
		if (!SorcererMetamagicManager.consumePending(casterSheet)) return;
		//consumePending solo quita el flag del JsonObject. Sin esto el gasto vivia unicamente en RAM hasta el
		//autoguardado: reiniciar antes de que saltara devolvia el Hechizo Gemelo armado y se podia gemelar
		//gratis otra vez (invariante 4). Armarlo si se persistia; gastarlo no.
		SheetLoader.saveServer(casterSheet, caster.getStringUUID());

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
				e -> e != excluding && isSpellTarget(caster, e))) {
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

	/**
	 * <p>Objetivos dentro del área, con oclusión de terreno real: uno dentro de la forma solo cuenta si
	 * hay línea de visión libre de bloques sólidos desde el origen del efecto hasta él (una pared lo
	 * protege, igual que en 5e).</p>
	 *
	 * <p>La esfera nace en el punto de impacto; la línea y el cono nacen en el LANZADOR y salen hacia
	 * donde mira. Esa diferencia es la razón de que existan como formas propias en vez de aproximarse con
	 * un radio: un cono convertido en radio golpea a todo lo que el lanzador tiene detrás, incluido su
	 * propio grupo.</p>
	 */
	private static List<Entity> findAoeTargets(ServerPlayer caster, Vec3 center, double radius, String shape) {
		boolean fromCaster = "line".equals(shape) || "cone".equals(shape);
		Vec3 origin = fromCaster ? caster.getEyePosition(1.0f) : center;
		Vec3 direction = caster.getViewVector(1.0f).normalize();

		//La caja de búsqueda cubre el alcance máximo de la forma en cualquier dirección; el filtro fino lo
		//hace inShape más abajo. Buscar de más aquí es barato y evita geometría de cajas orientadas.
		AABB box = new AABB(origin, origin).inflate(radius);
		List<Entity> candidates = caster.level().getEntities((Entity) null, box,
			entity -> isSpellTarget(caster, entity));

		List<Entity> hit = new ArrayList<>();
		for (Entity entity : candidates) {
			Vec3 point = entity.getBoundingBox().getCenter();
			if (!inShape(shape, origin, direction, radius, point)) continue;
			if (hasClearPath(caster, origin, point)) hit.add(entity);
		}
		return hit;
	}

	//Un cono de 5e es tan ancho como largo en cualquier punto de su longitud, lo que da un semiángulo de
	//atan(0.5) ≈ 26,57°. Se guarda su coseno para comparar con un producto escalar y ahorrarse el arcocoseno
	//en cada objetivo candidato de cada lanzado.
	private static final double CONE_COS_HALF_ANGLE = Math.cos(Math.atan(0.5));

	//Una línea de 5e mide 5 pies de ancho = 1 bloque, así que medio bloque a cada lado del eje.
	private static final double LINE_HALF_WIDTH = 0.5;

	//Muro de Fuego y compañía: 20 pies de alto (4 bloques) y 1 pie de grosor, redondeado a medio bloque a
	//cada lado para que quepa una hitbox y no haya que atravesarlo con precisión de píxel.
	static final double WALL_HEIGHT = 4.0;
	static final double WALL_HALF_THICKNESS = 0.5;

	//Package-private, no privado: es geometria pura (sin mundo, sin entidades) y es justo la clase de
	//logica que conviene fijar en JsonContentSelfTest — un cono que se abre al reves no falla en ningun
	//sitio, simplemente golpea al grupo propio en vez de al enemigo.
	static boolean inShape(String shape, Vec3 origin, Vec3 direction, double length, Vec3 point) {
		Vec3 offset = point.subtract(origin);
		double along = offset.dot(direction); //Proyección sobre el eje: negativa = está detrás del lanzador.

		return switch (shape) {
			//Distancia perpendicular al eje, acotando la proyección al segmento para que un objetivo pasado
			//el final del rayo no cuente por estar cerca de la recta infinita.
			case "line" -> along >= 0 && along <= length
				&& offset.subtract(direction.scale(along)).length() <= LINE_HALF_WIDTH;
			//Dentro del alcance Y dentro del ángulo. El caso de longitud 0 se descarta antes por along >= 0.
			case "cone" -> along > 0 && offset.length() <= length
				&& along / offset.length() >= CONE_COS_HALF_ANGLE;
			//Un muro es una SUPERFICIE, no un cilindro: la distancia al eje se mide solo en horizontal, y
			//la altura se comprueba aparte. Medirla en 3D como la línea daría un tubo, y alguien de pie
			//justo encima o debajo del muro se llevaría el daño sin haberlo tocado.
			case "wall" -> {
				if (along < 0 || along > length) yield false;
				double vertical = point.y - origin.y;
				if (vertical < 0 || vertical > WALL_HEIGHT) yield false;
				Vec3 flatOffset = new Vec3(offset.x, 0, offset.z);
				Vec3 flatAxis = new Vec3(direction.x, 0, direction.z).normalize();
				double flatAlong = flatOffset.dot(flatAxis);
				yield flatOffset.subtract(flatAxis.scale(flatAlong)).length() <= WALL_HALF_THICKNESS;
			}
			default -> offset.length() <= length;
		};
	}

	//ponytail: un solo rayo al centro de la hitbox del objetivo, no varios puntos de su volumen ni un
	//cálculo de cobertura parcial — alcanza para "un muro entero bloquea, un hueco en la pared no", que es
	//lo que un área necesita decidir. La cobertura PARCIAL ya la calcula Cover con sus cinco rayos para las
	//tiradas de ataque y las salvaciones; aquí solo se pregunta si el efecto llega o no llega.
	private static boolean hasClearPath(ServerPlayer caster, Vec3 from, Vec3 to) {
		return !Cover.isBlocked(caster.level(), from, to, caster);
	}

	/**
	 * <p>Objetivo válido de un hechizo: <b>cualquier criatura viva</b>. Antes eran solo jugadores y
	 * enemigos, y eso dejaba media mesa intocable — una vaca, un aldeano, un caballo o un lobo domesticado
	 * no se podían quemar, curar ni dormir, y el hechizo se rechazaba como si no hubiera nadie delante. En
	 * 5e apuntar es libre: la regla decide qué le pasa al objetivo, no si se puede apuntar.</p>
	 *
	 * <p>El resto del camino ya sabía tratar a una criatura sin ficha ni bloque de estadísticas (CA 10 en
	 * {@link #armorClassOfEntity}, salvación con d20 pelado en {@code SaveRules}, salud vanilla en
	 * {@link #applyDamage} y {@link #healTarget}), así que no hacía falta nada más que dejarla entrar.</p>
	 */
	private static boolean isSpellTarget(ServerPlayer caster, Entity entity) {
		return entity != caster && entity.isAlive() && entity instanceof LivingEntity;
	}

	//Mismo raycast que usa Minecraft internamente para saber a qué le pegó una flecha, reutilizado para
	//apuntar el hechizo a lo que el lanzador tenga delante.
	private static Entity findTarget(ServerPlayer caster) {
		Vec3 eyePos = caster.getEyePosition(1.0f);
		Vec3 viewVec = caster.getViewVector(1.0f);
		Vec3 endPos = eyePos.add(viewVec.scale(RANGE));
		AABB searchBox = caster.getBoundingBox().expandTowards(viewVec.scale(RANGE)).inflate(1.0);

		EntityHitResult hit = ProjectileUtil.getEntityHitResult(caster.level(), caster, eyePos, endPos, searchBox,
			entity -> isSpellTarget(caster, entity));
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

		//Un conjuro de ataque apunta igual que una flecha, así que el parapeto cuenta igual (ver Cover).
		Cover cover = Cover.between(caster, target);
		int targetAc = armorClassOfEntity(target) + cover.bonus();
		String targetName = nameOf(target);

		if (attackRoll.criticalMiss() || (!attackRoll.criticalHit() && attackRoll.outcome().result().getValue() < targetAc)) {
			ChatFeedback.broadcast(caster, ChatFeedback.withCover(ChatFeedback.attackResult(casterName, targetName, spell.name(), attackRoll.outcome().formatted(), targetAc, false, null, inspiration), cover));
			return;
		}

		DiceManager.DamageResult damageRoll = DiceManager.rollDamage(new JsonObject(), spell.dice(), attackRoll.criticalHit());
		if (damageRoll.formatted() == null) return;

		applyDamage(target, damageRoll.amount(), spell.damageType());
		CombatFx.hit(target, attackRoll.criticalHit(), spell.damageType());
		ChatFeedback.broadcast(caster, ChatFeedback.withCover(ChatFeedback.attackResult(casterName, targetName, spell.name(), attackRoll.outcome().formatted(), targetAc, true, damageRoll.formatted(), inspiration), cover));
		applySpellEffect(caster, spell, target);
	}

	private static void castSaveSpell(ServerPlayer caster, String casterName, SpellRegistry.Spell spell, Entity target, int proficiency, int abilityMod) {
		if (TurnManager.isMonster(target)) MonsterRegistry.faceTarget(target, caster);
		//Cobertura, CD real, salvación y daño final: mismas reglas, y mismo código, que cuando el que lanza
		//es un monstruo (ver SaveRules).
		SaveRules.Outcome save = SaveRules.resolve(caster, target, spell.saveAbility(),
			8 + proficiency + abilityMod, spell.dice(), spell.halfOnSave());
		if (save == null) return;
		boolean saved = save.saved();

		CombatFx.spellImpact(target, saved, spell.damageType());
		ChatFeedback.broadcast(caster, ChatFeedback.withLegendaryResistance(
			ChatFeedback.withCover(ChatFeedback.saveResult(casterName, nameOf(target), spell.name(),
				save.roll().formatted(), save.dc(), saved, save.label(), save.damageFormatted()), save.cover()),
			save.legendaryResistance(), MonsterRegistry.legendaryResistancesLeft(target)));

		if (save.finalDamage() > 0) applyDamage(target, save.finalDamage(), spell.damageType());
		//El efecto depende de la SALVACIÓN, no del daño: en 5e que una condición prenda lo decide si el
		//objetivo superó la tirada, y hay hechizos enteros que no hacen daño ninguno (Inmovilizar Persona,
		//Dormir). Antes esto colgaba de "finalDamage > 0", así que un hechizo de solo condición no aplicaba
		//nada y uno que sí hacía daño imponía su condición incluso al que había superado la salvación.
		if (!saved) applySpellEffect(caster, spell, target);
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

	//Público: también lo usa TurnManager para el daño de efectos de estado (veneno, etc.) al inicio del turno.
	public static void applyDamage(Entity target, int amount) {
		applyDamage(target, amount, null);
	}

	public static void applyDamage(Entity target, int amount, String damageType) {
		//Esto eran ~35 líneas con un instanceof Player decidiendo cómo aplicar el daño, quién trackea los PG
		//y cómo matar — exactamente la duplicación que Combatant vino a borrar, y que además dejaba a los
		//monstruos sin resistencias frente al daño de conjuro. Un conjuro siempre cuenta como mágico.
		Combatant combatant = Combatant.of(target);
		if (combatant != null) {
			combatant.takeDamage(DamageTypes.applyMultiplier(amount, combatant.effectiveDamageMultiplier(damageType, true)));
			return;
		}
		//Mob de compatibilidad (Enemy de otro mod o vanilla, ver TurnManager.isMonster): no tiene bloque de
		//estadísticas ni ficha, así que su PG real ES su salud vanilla — hurt() ya dispara solo el camino de
		//muerte vanilla (loot, XP, sonido), sin nada manual.
		if (target instanceof LivingEntity living) living.hurt(target.damageSources().generic(), amount);
	}

	private static int armorClassOfEntity(Entity target) {
		Combatant combatant = Combatant.of(target);
		//10 es la CA de referencia de 5e para algo sin armadura ni destreza: es lo que le queda a un mob de
		//compatibilidad, que no tiene ni hoja ni bloque de estadísticas del que sacarla.
		return combatant != null ? combatant.armorClass() : 10;
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

	//Antes reenviaban la hoja completa en cada hechizo lanzado — ahora solo los campos que de verdad
	//cambiaron. Son DOS, no uno: la tabla por nivel y el total que sale de
	//ella (ver SpellSlots.clientPatch); mandando solo el total, el Grimorio se quedaba enseñando columnas
	//viejas y ofreciendo niveles ya gastados.
	private static void sendSlotsUpdate(ServerPlayer player, JsonObject sheet) {
		DndsheetsMod.sendSheetFieldUpdate(player, SpellSlots.clientPatch(sheet));
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
