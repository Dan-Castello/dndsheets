package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.network.TurnStateMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>Modo turnos: ordena una lista de combatientes por iniciativa y aplica efectos de estado (veneno,
 * etc.) al empezar el turno de cada uno. Cada combatiente (jugador O monstruo controlado por el DM)
 * tiene derecho a UNA acción por turno; cualquier intento extra —sea porque ya actuó este turno, o
 * porque no le toca— se IGNORA por completo, no se acumula para más tarde. Al llegarle el turno a
 * alguien, elige de cero qué hacer; nada de lo que intentó fuera de turno queda pendiente.</p>
 *
 * <p>Quien tiene el turno tampoco se mueve con libertad total de Minecraft: solo puede alejarse de
 * donde empezó su turno hasta su "speed" de hoja (5 pies = 1 bloque, 30 pies por defecto si el campo
 * está vacío o no es un número); pasado eso se le devuelve a la última posición válida — ver
 * {@link MovementAnchorTracker#enforceMovementBudget}.</p>
 *
 * <p>El turno pasa solo: en cuanto {@link #tryAct} le acepta una acción a quien tiene el turno, un tick
 * después se avanza al siguiente combatiente sin que nadie escriba {@code /dndturns next} — ver
 * {@link #scheduleAutoAdvance}. Quien tiene el turno brilla (efecto vanilla Brillo) para que se note sin
 * leer el chat, y el estado (ronda, de quién es el turno, si ya actuó) se manda a todos los clientes para
 * el HUD — ver {@code network.TurnStateMessage} / {@code client.TurnHudOverlay}.</p>
 *
 * <p>Fuera de modo turnos ({@link #isActive()} falso), todo se comporta exactamente igual que antes:
 * nada de esto interfiere si nadie usa {@code /dndturns start}.</p>
 *
 * <p>Todo mutador de estado (start/next/cancel) pasa por un guardado de un solo tick de servidor, para
 * no duplicar un avance si el mismo comando se dispara dos veces por accidente — mismo problema que ya
 * se resolvió para el lanzado de hechizos en {@link SpellCastManager}.</p>
 */
@Mod.EventBusSubscriber
public class TurnManager {
	//isMonster se fija al armar la iniciativa (startAt), no se reinfiere después: si el
	//combatiente se borra del mundo a mitad de encuentro (DM, Vara de DM...) ya no habría forma de
	//preguntarle a MonsterRegistry qué era. Ver allEnemiesDefeated, que depende de este flag. playerUuid es
	//null para monstruos; para jugadores sobrevive a un entityId que cambia al reconectarse — ver
	//reconcilePlayerEntity, que lo usa para encontrar el puesto de este jugador en order tras un relog.
	//ponytail: nada de esto se persiste en disco (probado y revertido a propósito — un mob de
	//compatibilidad recuperado tras un reinicio no recordaba su NoAI/movimiento a medio turno, quedando en
	//un estado más raro que simplemente perder el encuentro). Las sesiones de este mod son al momento; un
	//reinicio de servidor a mitad de combate simplemente lo corta, como cualquier otra cosa en memoria.
	public record TurnEntry(int entityId, String name, boolean isMonster, String playerUuid) {}
	public record StatusEffect(String name, String damageDice, int remainingTurns) {}

	private static final List<TurnEntry> order = new ArrayList<>();
	private static int currentIndex = -1;
	private static int round = 0;
	private static boolean active = false;
	private static long lastActionTick = -1;

	//Área desde donde arrancó el encuentro (ver startAt): se reescanea sola cada LATE_MONSTER_SCAN_INTERVAL_
	//TICKS mientras siga activo, sumando al orden cualquier mob hostil que entre después de arrancar (una
	//araña que se acerca a media pelea, p.ej.) — sin esto, solo entraban los que ya estaban dentro en el
	//instante exacto de /dndturns start. combatOrigin null = sin escaneo pendiente (encuentro terminado).
	private static Vec3 combatOrigin;
	private static double combatRadius;
	private static final int LATE_MONSTER_SCAN_INTERVAL_TICKS = 20; //1s: no hace falta notar a alguien nuevo al instante.

	//Generación del turno actual: sube cada vez que el turno de verdad avanza (advance) o que se deshace
	//una acción (undoAction). scheduleAutoAdvance captura el valor vigente al encolar el auto-avance y solo
	//lo ejecuta si nadie lo cambió mientras tanto — sin esto, deshacer una acción y volver a actuar (p.ej.
	//con el ítem "Deshacer Turno") podía disparar el auto-avance viejo Y el nuevo, dando una acción extra
	//gratis, y un /dndturns next manual justo antes del auto-avance podía hacer avanzar la ronda dos veces.
	private static int turnToken = 0;

	//Mobs de compatibilidad (Enemy sin bloque de estadísticas propio, ver isMonster): a diferencia de los
	//monstruos propios (NoAI fijo desde que se invocan, ver MonsterRegistry.spawnAt), estos SÍ necesitan su
	//IA vanilla real para moverse/atacar en su propio turno — se les apaga (freeze) mientras esperan y se
	//les devuelve (beginTurn) en cuanto les toca, igual que el anclaje de posición hace con un jugador.
	//originalNoAi recuerda cómo estaba el mob ANTES de que el modo turnos tocara nada, para devolverlo tal
	//cual al terminar el combate (start/end) en vez de asumir que siempre era false — por si algún otro
	//mod ya lo controlaba con NoAI por su cuenta. Esta memoria en RAM no sobrevive un chunk descargado
	//(justo lo que pasa casi siempre al morir: el respawn aleja al jugador de la zona en el mismo instante
	//en que hay que restaurar) — FROZEN_TAG es el respaldo: una etiqueta en el NBT propio de la entidad
	//(sí sobrevive descarga/recarga e incluso reinicio del servidor), leída por onCompatMobLoaded en
	//cuanto la entidad vuelve a cargar para devolverle la IA sola si ya no sigue en combate.
	private static final Map<Integer, Boolean> originalNoAi = new HashMap<>();
	private static final String FROZEN_TAG = "dndsheets_turn_frozen";

	//Red de seguridad para el turno de un mob de compatibilidad: ni "atacó de verdad" (onMobTurnAttack) ni
	//"agotó su movimiento" (onMobTick) están garantizados a pasar nunca — el caso real que lo destapó es el
	//slime más pequeño, que por diseño vanilla NUNCA hace daño al tocar (Slime.playerTouch exige
	//!isTiny()), así que jamás dispara el evento de ataque; si además no se aleja lo suficiente de su
	//origen, tampoco agota movimiento, y su turno se quedaba esperando para siempre otra vez. Con esto, a
	//los MOB_TURN_TIMEOUT_TICKS de haber empezado su turno se le corta igual, actúe o no.
	private static final Map<Integer, Long> mobTurnStartTick = new HashMap<>();
	private static final long MOB_TURN_TIMEOUT_TICKS = 30; //1.5s a 20 ticks/seg.

	private static void setCompatMobActive(Entity entity, boolean active) {
		if (!(entity instanceof Mob mob)) return;
		originalNoAi.putIfAbsent(mob.getId(), mob.isNoAi());
		mob.setNoAi(!active);
		if (active) mob.getPersistentData().remove(FROZEN_TAG);
		else mob.getPersistentData().putBoolean(FROZEN_TAG, true);
	}

	private static void restoreAllCompatMobAi(ServerLevel level) {
		for (Integer id : new ArrayList<>(originalNoAi.keySet())) {
			Entity entity = level.getEntity(id);
			if (entity instanceof Mob mob) {
				mob.setNoAi(originalNoAi.get(id));
				mob.getPersistentData().remove(FROZEN_TAG);
			}
		}
		originalNoAi.clear();
	}

	//Respaldo de restoreAllCompatMobAi para cuando la entidad no estaba cargada al terminar el combate
	//(chunk descargado): en cuanto vuelve a cargar (spawn, chunk load, incluso tras reiniciar el
	//servidor), si lleva la etiqueta puesta y ya no forma parte de un combate en curso, se le devuelve la
	//IA sola en vez de quedarse congelada para siempre esperando que alguien la edite a mano.
	@SubscribeEvent
	public static void onCompatMobLoaded(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
		if (event.getLevel().isClientSide()) return;
		if (!(event.getEntity() instanceof Mob mob) || !mob.getPersistentData().getBoolean(FROZEN_TAG)) return;
		if (active && isInOrder(mob.getId())) return; //Sigue en combate de verdad: freeze()/beginTurn ya lo manejan.
		mob.setNoAi(false);
		mob.getPersistentData().remove(FROZEN_TAG);
	}

	//Ids de combatientes confirmados muertos/borrados de verdad (ver markDefeated). allEnemiesDefeated NO
	//puede fiarse de level.getEntity(id)==null para inferir "muerto": un monstruo simplemente en un chunk
	//descargado (nadie cerca en ese instante) también devuelve null, y sin esta distinción el combate
	//terminaba solo con el monstruo perfectamente vivo en cuanto se alejaban lo suficiente.
	private static final Set<Integer> confirmedDefeated = new HashSet<>();

	//Público: llamado por CombatManager/SpellCastManager/MonsterActionManager justo donde de verdad
	//eliminan a un monstruo (remove(RemovalReason...)) — ese remove nunca dispara LivingDeathEvent (los
	//monstruos de este mod no mueren por el camino vanilla de LivingEntity#die()), así que no hay otro
	//punto genérico donde enterarse de una muerte real.
	public static void markDefeated(int entityId) {
		confirmedDefeated.add(entityId);
	}

	//Complemento del markDefeated de arriba: cubre la muerte que SÍ pasa por el camino vanilla de verdad
	//(LivingEntity#die(), que markDefeated explícitamente no cubre) — un monstruo zombie (p.ej. goblin) que
	//se quema con el sol, se ahoga o cae al vacío muere así, no por un remove(RemovalReason...) nuestro. Sin
	//esto, allEnemiesDefeated nunca se enteraba de esa muerte (no está en confirmedDefeated y
	//level.getEntity(id) puede seguir devolviendo la entidad ya muerta un instante, o null si ya se
	//descargó, que a propósito se trata como "sigue viva" para no cerrar el combate de más) — si esa muerte
	//coincidía justo con que le tocara su turno, el combate se quedaba dando vueltas sin poder terminar solo.
	@SubscribeEvent
	public static void onMonsterDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!isMonster(event.getEntity())) return;
		markDefeated(event.getEntity().getId());
		if (event.getEntity().level() instanceof ServerLevel level) checkAllEnemiesDefeated(level);
	}

	//Consumo de turno para mobs de compatibilidad (Enemy sin bloque de estadísticas propio): no hay
	//resolveAttack que llamar como con los monstruos propios (ver MonsterActionManager.autoAct), así que
	//se les deja atacar de verdad con su IA vanilla (reactivada en beginTurn) y se detecta ESE golpe como
	//su acción del turno. Si ya había gastado su acción (dos golpes rápidos antes de que el auto-avance de
	//un tick alcance a procesarse), el segundo se cancela — mismo límite de "una acción por turno" que ya
	//aplica a los jugadores en CombatManager. instanceof Mob (no solo "no jugador") es a propósito: un
	//golpe de JUGADOR también puede llegar hasta LivingAttackEvent (p.ej. contra un mob de compatibilidad,
	//que CombatManager no cancela si acierta) — CombatManager ya llamó tryAct por él, así que procesarlo
	//DE NUEVO acá lo encontraría "ya actuado" y cancelaría el golpe del propio jugador que sí debía pasar.
	@SubscribeEvent
	public static void onMobTurnAttack(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		Entity attacker = event.getSource().getEntity();
		if (!(attacker instanceof Mob mob) || MonsterRegistry.statBlockOf(attacker) != null) return;

		//Congelado por nosotros (no es su turno): NoAI apaga su selector de metas (perseguir, apuntar...)
		//pero NO el daño de contacto/toque que corre aparte de eso — Slime.playerTouch, p.ej., se dispara
		//por colisión de hitbox en cada tick sin importar NoAI. Sin esto, un slime aplastado contra el
		//jugador seguía haciendo daño aunque llevara su turno congelado. originalNoAi.containsKey confirma
		//que fuimos NOSOTROS quienes lo congelamos (y no NoAI puesto por otra razón ajena al modo turnos).
		if (mob.isNoAi() && originalNoAi.containsKey(mob.getId())) {
			event.setCanceled(true);
			return;
		}

		if (!isCurrentActor(attacker)) return;
		if (!tryAct(attacker)) event.setCanceled(true);
	}

	//Un mob que golpea a un jugador desde FUERA del área del encuentro (un esqueleto o un ghast atacando a
	//distancia, p.ej.) nunca lo capturaba startAt ni el reescaneo periódico (ambos acotados al radio desde
	//donde arrancó el combate) — se quedaba golpeando gratis, sin turno ni congelamiento. Cualquier golpe
	//de un mob a un jugador lo suma al combate (arrancándolo si hace falta, centrado en la VÍCTIMA, no en
	//el atacante, que puede estar lejísimos) o lo mete a mano si ya había uno en marcha.
	@SubscribeEvent
	public static void onMobHitsPlayer(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		Entity attacker = event.getSource().getEntity();
		if (attacker == null || !isMonster(attacker) || !(attacker.level() instanceof ServerLevel level)) return;

		if (!active) startAt(level, player.position(), DEFAULT_RADIUS);
		if (active && !isInOrder(attacker.getId())) addLateMonster(level, attacker, nameOf(attacker));
	}

	//Presupuesto de movimiento de esos mismos mobs: si agotan su velocidad (ver
	//MovementAnchorTracker.speedBlocksForMob) sin llegar a golpear a nadie —persiguiendo a alguien fuera de
	//su alcance, p.ej.—, se les acaba el turno igual que si hubieran actuado. Sin esto, nada más haría
	//avanzar su turno y todos (jugador incluido) se quedarían esperando para siempre otra vez.
	@SubscribeEvent
	public static void onMobTick(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
		//LivingTickEvent dispara para CADA entidad viva del mundo, cada tick, cliente y servidor — este
		//chequeo va primero a propósito: fuera de combate (la inmensa mayoría del tiempo de juego) corta
		//antes de tocar siquiera la entidad o su Level, en vez de pagar isClientSide/instanceof/isCurrentActor
		//por cada mob del servidor 20 veces por segundo para nada.
		if (!active) return;
		if (event.getEntity().level().isClientSide()) return;
		Entity entity = event.getEntity();
		if (entity instanceof Player || !isCurrentActor(entity) || MonsterRegistry.statBlockOf(entity) != null) return;

		Long startTick = mobTurnStartTick.get(entity.getId());
		boolean timedOut = startTick != null && entity.level().getGameTime() - startTick >= MOB_TURN_TIMEOUT_TICKS;
		boolean outOfMovement = movementAnchors.enforceMobMovementBudget(entity, MovementAnchorTracker.speedBlocksForMob(entity));
		if (timedOut || outOfMovement) tryAct(entity);
	}

	//Público: mismo criterio que usa startAt para decidir quién cuenta como "monstruo" en la iniciativa —
	//reutilizado por CombatManager para saber si golpear un mob sin bloque de estadísticas propio (jefe o
	//enemigo de otro mod) debe igualmente enganchar el modo turnos. Enemy es la interfaz vanilla que
	//cualquier mob hostil (propio o de otro mod) ya implementa para que el resto de Minecraft/Forge lo
	//trate como hostil — reusarla evita mantener una lista de compatibilidad aparte por mod.
	/**
	 * <p>Si cuenta como <b>enemigo</b>. Se usa para decidir cuándo termina un encuentro, así que un PNJ
	 * aliado con ficha NO entra aquí a propósito: si entrara, un tabernero en la sala impediría que el
	 * combate terminara nunca.</p>
	 */
	public static boolean isMonster(Entity entity) {
		return MonsterRegistry.monsterIdOf(entity) != null || entity instanceof Enemy;
	}

	/**
	 * <p>Si es un objetivo válido de las reglas de combate, sea enemigo o no. Un PNJ con ficha
	 * ({@link Combatant#characterIdOf}) es atacable y curable con reglas de 5e completas sin ser un
	 * enemigo — separar las dos preguntas es lo que permite tener aliados sin romper el fin de combate.</p>
	 */
	public static boolean isCombatTarget(Entity entity) {
		return isMonster(entity) || Combatant.characterIdOf(entity) != null;
	}

	//Público: llamado justo tras markDefeated cuando un monstruo se borra A MANO en mitad de combate (Vara
	//de DM, clic derecho + agachado). Sin esto, si era el último enemigo con vida, el combate seguía
	//"activo" hasta que le tocara el turno a alguien de nuevo (el único punto que ya comprobaba
	//allEnemiesDefeated) en vez de terminar al instante — quedaba el HUD/turnos corriendo sobre un
	//encuentro ya vacío.
	public static void checkAllEnemiesDefeated(ServerLevel level) {
		if (active && allEnemiesDefeated(level)) {
			broadcast(level, Component.translatable("chat.dndsheets.turn.all_enemies_defeated").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			end(level);
		}
	}

	private static final Map<Integer, List<StatusEffect>> effects = new HashMap<>();

	//Anclaje de posición y presupuesto de movimiento del modo turnos — ver MovementAnchorTracker
	//(AUDIT_REPORT_2026.md F3).
	private static final MovementAnchorTracker movementAnchors = new MovementAnchorTracker();

	//Rasgos con duración en asaltos (Furia del bárbaro, etc.) en vez de ticks reales — ver
	//BarbarianRageManager para el caso de uso. Un asalto = una vuelta completa del orden de turnos.
	private record PendingRoundCallback(int roundsRemaining, Runnable action) {}
	private static final List<PendingRoundCallback> pendingRoundCallbacks = new ArrayList<>();

	//Quién ya gastó su acción en SU turno actual. Sin esto, quien tiene el turno podía atacar tantas
	//veces como quisiera con solo hacer clic repetido — el modo turnos era puramente decorativo.
	private static final Set<Integer> actedThisTurn = new HashSet<>();

	//Reacciones (Ataque de Oportunidad, Escudo, Contrahechizo): a diferencia de actedThisTurn, se pueden
	//gastar en el turno de CUALQUIERA, no solo el propio — por eso es un set aparte en vez de reusar
	//actedThisTurn. Se recupera al empezar el turno propio (beginTurn), igual que la regla real de 5e.
	//ponytail: solo se recupera para quien esté en el orden de turnos; un jugador con un ítem de reacción
	//que nunca se metió en la iniciativa se quedaría con la reacción gastada para siempre — caso raro,
	//ya que /dndturns start mete a todos los jugadores conectados en el radio.
	private static final Set<Integer> reactionUsed = new HashSet<>();

	//Ataques de oportunidad del modo turnos — ver OpportunityAttackTracker (AUDIT_REPORT_2026.md F3).
	private static final OpportunityAttackTracker opportunityAttacks = new OpportunityAttackTracker();

	//Público: usado por Escudo/Contrahechizo (fuera de turno) y por el ataque de oportunidad de abajo
	//(dentro del tick de quien se mueve). Mismo "una vez y se acabó" que tryAct, pero sin exigir que sea
	//el turno del que reacciona.
	public static boolean tryReact(Entity actor) {
		if (!active) return true;
		return reactionUsed.add(actor.getId());
	}

	public static boolean isActive() {
		return active;
	}

	private static TurnEntry current() {
		return currentIndex >= 0 && currentIndex < order.size() ? order.get(currentIndex) : null;
	}

	/**
	 * <p>Si no hay modo turnos activo, siempre permite actuar (comportamiento normal, sin cambios). Si
	 * hay modo turnos activo, {@code actor} (jugador o monstruo) solo puede actuar UNA vez durante su
	 * propio turno: la primera llamada marca la acción como usada y devuelve true; cualquier intento
	 * extra —fuera de turno, o repetido dentro de su propio turno— devuelve false sin dejar rastro para
	 * más tarde (no se encola nada). El llamador debe comprobar el resultado ANTES de gastar cualquier
	 * recurso (espacios de conjuro, etc.), para no cobrar por una acción que se va a descartar.</p>
	 */
	public static boolean tryAct(Entity actor) {
		//Antes que nada del modo turnos, y también fuera de combate: una condición que incapacita
		//(paralizado, aturdido, petrificado, inconsciente) impide actuar aunque no haya iniciativa activa.
		//Aquí y no en cada llamador porque TODA ruta de ataque —cuerpo a cuerpo, proyectil, PvP, hechizo—
		//pasa ya por este mismo punto.
		Combatant combatant = Combatant.of(actor);
		if (combatant != null && combatant.cannotAct()) return false;
		if (!active) return true;
		TurnEntry currentEntry = current();
		if (currentEntry == null || currentEntry.entityId() != actor.getId()) return false;
		boolean acted = actedThisTurn.add(actor.getId());
		//En cuanto se gasta la acción, el turno se acaba solo: nadie tiene que escribir /dndturns next.
		//Se difiere un tick para que el chat/HUD del ataque que acaba de pasar se vea antes del "turno de...".
		if (acted && actor.level() instanceof ServerLevel level) {
			broadcastTurnState(level);
			scheduleAutoAdvance(level, actor.getId());
		}
		return acted;
	}

	//Mensaje uniforme para cuando tryAct() devuelve false, distinguiendo "no te toca" de "ya actuaste".
	public static void notifyCantAct(Entity actor) {
		if (!(actor instanceof Player player)) return;
		//Una condición incapacitante manda sobre cualquier otra explicación: decir "no es tu turno" a quien
		//está paralizado es exactamente el tipo de mensaje que hace que un cambio que SÍ funciona parezca roto.
		Combatant combatant = Combatant.of(actor);
		if (combatant != null && combatant.cannotAct()) {
			String blocking = combatant.conditions().stream()
				.filter(Condition::preventsActions).findFirst().map(Condition::label).orElse("");
			player.sendSystemMessage(Component.translatable("chat.dndsheets.condition.cant_act", blocking).withStyle(ChatFormatting.RED));
			return;
		}
		TurnEntry currentEntry = current();
		boolean isCurrentActor = currentEntry != null && currentEntry.entityId() == actor.getId();
		Component reason = isCurrentActor
			? Component.translatable("chat.dndsheets.turn.already_acted")
			: Component.translatable("chat.dndsheets.turn.not_your_turn",
				currentEntry != null ? currentEntry.name() : Component.translatable("chat.dndsheets.turn.other_combatant"));
		player.sendSystemMessage(reason.copy().withStyle(ChatFormatting.RED));
	}

	//Un tick después de gastar la acción, si sigue siendo el mismo combatiente (nadie avanzó a mano de
	//por medio), pasa el turno solo. El tick de margen deja que se vea el resultado de la acción antes del
	//anuncio de ronda siguiente, y evita reentrar en advance() en medio de la resolución del ataque/hechizo
	//que todavía está corriendo cuando tryAct devuelve true.
	private static void scheduleAutoAdvance(ServerLevel level, int entityId) {
		int scheduledToken = turnToken;
		DndsheetsMod.queueServerWork(1, () -> {
			TurnEntry stillCurrent = current();
			if (!active || stillCurrent == null || stillCurrent.entityId() != entityId || turnToken != scheduledToken) return;
			advance(level);
		});
	}

	//Usado por los ítems de comodidad (TurnItemManager): solo quien tiene el turno puede usarlos.
	public static boolean isCurrentActor(Entity actor) {
		TurnEntry currentEntry = current();
		return active && currentEntry != null && currentEntry.entityId() == actor.getId();
	}

	//"Deshacer turno": le devuelve su acción a quien tiene el turno ahora mismo, sin perder su lugar en
	//el orden ni pasarle el turno a nadie más — para corregir un ataque hecho por error, p.ej.
	public static void undoAction(ServerLevel level, Entity actor) {
		if (!isCurrentActor(actor)) return;
		actedThisTurn.remove(actor.getId());
		turnToken++; //Invalida cualquier auto-avance ya encolado por la acción que se acaba de deshacer.
		broadcast(level, Component.translatable("chat.dndsheets.turn.undo", current().name()).withStyle(ChatFormatting.YELLOW));
		broadcastTurnState(level);
	}

	public static final double DEFAULT_RADIUS = 30.0;

	//Tira iniciativa (1d20 + Destreza/mod) para todos los jugadores y monstruos invocados dentro de un
	//radio, ordena de mayor a menor y arranca el modo turnos. Público: lo usan tanto TurnCommand
	//(/dndturns start) como el Panel de DM (network.TurnControlMessage) y CombatManager.autoStartCombatIfNeeded
	//(el primer golpe de un jugador a un monstruo arranca el combate solo si no había uno activo) — vivía
	//antes en TurnCommand, invirtiendo la dependencia (lógica de dominio llamando a la capa de comandos),
	//ver AUDIT_TECHNICAL.md M-ARQ-1.
	public static int startAt(ServerLevel level, Vec3 pos, double radius) {
		//ponytail: solo se admite un combate a la vez en todo el servidor (ver el comentario de más abajo
		//sobre debounce/guardado global) — antes, si dos grupos jugaban en zonas distintas del mapa, el
		//segundo /dndturns start (o el primer golpe que dispara autoStartCombatIfNeeded) pisaba el
		//combate del primero EN SILENCIO: turnToken subía, el orden viejo se borraba, y nadie del primer
		//grupo se enteraba de que su encuentro había desaparecido a mitad de pelea. Esto no habilita
		//combates simultáneos de verdad (seguiría haciendo falta el refactor completo para eso) — solo
		//convierte la corrupción silenciosa en un aviso claro cuando las dos zonas ni siquiera se tocan.
		if (active && combatOrigin != null && combatOrigin.distanceTo(pos) > radius + combatRadius) {
			for (ServerPlayer player : level.players()) {
				if (player.position().distanceToSqr(pos) <= radius * radius) {
					player.sendSystemMessage(Component.translatable("chat.dndsheets.turn.blocked_elsewhere").withStyle(ChatFormatting.RED));
				}
			}
			return 0;
		}

		AABB box = new AABB(pos.x - radius, pos.y - radius, pos.z - radius, pos.x + radius, pos.y + radius, pos.z + radius);

		record Rolled(int entityId, String name, int score, boolean isMonster, String playerUuid) {}
		List<Rolled> rolled = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, box, e -> e instanceof Player || isMonster(e))) {
			String playerUuid = entity instanceof Player player ? player.getStringUUID() : null;
			rolled.add(new Rolled(entity.getId(), nameOf(entity), rollInitiative(entity), isMonster(entity), playerUuid));
		}
		rolled.sort((a, b) -> b.score() - a.score());

		List<TurnEntry> combatants = new ArrayList<>();
		for (Rolled r : rolled) combatants.add(new TurnEntry(r.entityId(), r.name() + " (" + r.score() + ")", r.isMonster(), r.playerUuid()));

		if (combatants.isEmpty()) return 0;

		boolean wasActive = active;
		start(level, combatants);
		if (active) {
			//Se actualiza siempre (incluso en un reinicio en caliente con nuevo centro/radio), pero el escaneo
			//solo se re-encola si no había uno ya corriendo — el que ya está en marcha relee estos dos campos
			//frescos en cada pasada, así que un reinicio en caliente ya le llega el área nueva solo.
			combatOrigin = pos;
			combatRadius = radius;
			if (!wasActive) scheduleLateMonsterScan(level);
		}
		return combatants.size();
	}

	//Re-escanea el área del encuentro cada LATE_MONSTER_SCAN_INTERVAL_TICKS y se vuelve a encolar sola
	//mientras el combate siga activo — se corta de raíz en cuanto termina (ver end()).
	private static void scheduleLateMonsterScan(ServerLevel level) {
		DndsheetsMod.queueServerWork(LATE_MONSTER_SCAN_INTERVAL_TICKS, () -> {
			if (!active || combatOrigin == null) return;
			AABB box = new AABB(combatOrigin.x - combatRadius, combatOrigin.y - combatRadius, combatOrigin.z - combatRadius,
				combatOrigin.x + combatRadius, combatOrigin.y + combatRadius, combatOrigin.z + combatRadius);
			for (Entity entity : level.getEntities((Entity) null, box, e -> isMonster(e) && !isInOrder(e.getId()))) {
				addLateMonster(level, entity, nameOf(entity));
			}
			scheduleLateMonsterScan(level);
		});
	}

	private static boolean isInOrder(int entityId) {
		for (TurnEntry entry : order) if (entry.entityId() == entityId) return true;
		return false;
	}

	private static int rollInitiative(Entity entity) {
		if (entity instanceof Player player) {
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			DiceManager.RollOutcome outcome = DiceManager.roll(sheet != null ? sheet : new JsonObject(), "1d20 + $dex");
			return outcome.result() != null ? outcome.result().getValue() : 10;
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(entity);
		int mod = block != null ? block.abilityModifier("dex") : 0;
		DiceManager.RollOutcome outcome = DiceManager.roll(new JsonObject(), "1d20 + " + mod);
		return outcome.result() != null ? outcome.result().getValue() : 10;
	}

	private static String nameOf(Entity entity) {
		if (entity instanceof Player player) {
			return SheetLoader.characterNameOf(SheetLoader.getServerSheet(player.getStringUUID()), player);
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(entity);
		return block != null ? block.name() : entity.getName().getString();
	}

	public static void start(ServerLevel level, List<TurnEntry> rolledOrder) {
		if (debounce(level)) return;

		//Reinicio en caliente (un encuentro ya activo, p.ej. /dndturns start disparado dos veces o un golpe
		//que arranca un segundo combate por error): sin esto, el combatiente que tenía el turno del
		//encuentro VIEJO se quedaba con el efecto Brillo pegado para siempre (nada más lo limpiaba), y como
		//turnToken no subía, un auto-avance ya encolado del encuentro viejo podía colarse y saltarse el
		//primer turno del nuevo.
		if (active) {
			clearGlow(level, current());
			turnToken++;
			restoreAllCompatMobAi(level);
		}

		order.clear();
		effects.clear();
		actedThisTurn.clear();
		reactionUsed.clear();
		opportunityAttacks.clear();
		movementAnchors.clear();
		confirmedDefeated.clear();
		mobTurnStartTick.clear();
		order.addAll(rolledOrder);
		currentIndex = 0;
		round = 1;
		active = !order.isEmpty();
		if (!active) return;

		//Todos menos quien empieza quedan anclados donde estén parados ahora mismo.
		for (int i = 1; i < order.size(); i++) {
			freeze(level, order.get(i));
		}

		StringBuilder orderText = new StringBuilder();
		for (int i = 0; i < order.size(); i++) {
			if (i > 0) orderText.append(", ");
			orderText.append(i + 1).append(". ").append(order.get(i).name());
		}
		broadcast(level, Component.translatable("chat.dndsheets.turn.order_announce", orderText.toString()).withStyle(ChatFormatting.GOLD));
		//Ayuda para quien nunca jugó D&D: la primera vez que arranca un encuentro, explica la regla en una
		//línea. El resto de turnos ya no repite esto — el HUD (ver network.TurnStateMessage) se encarga.
		broadcast(level, Component.translatable("chat.dndsheets.turn.tutorial").withStyle(ChatFormatting.GRAY));
		beginTurn(level);
	}

	public static void next(ServerLevel level) {
		if (!active || debounce(level)) return;
		advance(level);
	}

	//Salta al siguiente combatiente. Como ya no se acumulan acciones en cola, esto simplemente adelanta
	//el turno (para saltar a alguien AFK, p.ej.), sin nada más que "cancelar".
	public static void cancel(ServerLevel level) {
		if (!active || debounce(level)) return;
		advance(level);
	}

	public static void end(ServerLevel level) {
		if (!active) return;
		clearGlow(level, current());
		restoreAllCompatMobAi(level);
		broadcast(level, Component.translatable("chat.dndsheets.turn.ended").withStyle(ChatFormatting.GRAY));
		order.clear();
		effects.clear();
		actedThisTurn.clear();
		reactionUsed.clear();
		opportunityAttacks.clear();
		movementAnchors.clear();
		confirmedDefeated.clear();
		mobTurnStartTick.clear();
		combatOrigin = null; //Corta el reescaneo de mobs tardíos (ver scheduleLateMonsterScan) en su próxima pasada.
		active = false;
		currentIndex = -1;
		round = 0;
		broadcastTurnState(level); //Con active=false ya, esto le dice a todos los HUD que se oculten.

		//Cualquier rasgo con duración en asaltos que quedara pendiente se da por terminado ya: sin modo
		//turnos no hay forma de seguir contando asaltos, y dejarlo colgado para siempre sería peor.
		List<Runnable> pending = new ArrayList<>();
		for (PendingRoundCallback callback : pendingRoundCallbacks) pending.add(callback.action());
		pendingRoundCallbacks.clear();
		pending.forEach(Runnable::run);
	}

	//Un monstruo invocado a mitad de encuentro (carta, /dndmonsters spawn, NPC genérico) no entraba nunca
	//en order: nunca podía actuar (tryAct comparaba contra un id que no estaba en la lista) y, si además
	//era el último con vida, allEnemiesDefeated daba por terminado el combate igual, con él todavía vivo y
	//hostil. Se mete justo después de quien tiene el turno ahora (actúa pronto, sin recalcular iniciativa
	//de todo el orden) — llamado desde MonsterRegistry.spawnAt (los cuatro caminos de invocación propios),
	//scheduleLateMonsterScan (mob de compatibilidad que entra al área) y onMobHitsPlayer (uno que golpea
	//desde fuera del área).
	public static void addLateMonster(ServerLevel level, Entity monster, String displayName) {
		if (!active) return;
		TurnEntry newEntry = new TurnEntry(monster.getId(), displayName, true, null);
		order.add(currentIndex + 1, newEntry);
		freeze(level, newEntry); //Congela de entrada a un mob de compatibilidad recién sumado (no le toca aún); no-op para uno propio (ya NoAI desde que se invocó).
		broadcast(level, Component.translatable("chat.dndsheets.turn.joins_combat", displayName).withStyle(ChatFormatting.GOLD));
		broadcastTurnState(level);
	}

	//Un jugador que llega DESPUÉS de que el combate ya arrancó (autoStartCombatIfNeeded solo capturó lo que
	//había a 30 bloques del monstruo en ESE instante) nunca entraba en order: tryAct le daba false para
	//siempre en este encuentro y su golpe se cancelaba sin aplicar ni el daño vanilla. Se suma justo
	//después de quien tiene el turno ahora (mismo patrón que addLateMonster) y queda anclado como
	//cualquier otro combatiente en espera, ya que no es su turno todavía. No hace nada si ya tenía puesto.
	public static void addLatePlayerIfMissing(ServerLevel level, ServerPlayer player) {
		if (!active) return;
		for (TurnEntry entry : order) {
			if (entry.entityId() == player.getId()) return;
		}
		String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(player.getStringUUID()), player);
		TurnEntry newEntry = new TurnEntry(player.getId(), name, false, player.getStringUUID());
		order.add(currentIndex + 1, newEntry);
		freeze(level, newEntry);
		broadcast(level, Component.translatable("chat.dndsheets.turn.joins_combat", name).withStyle(ChatFormatting.GOLD));
		broadcastTurnState(level);
	}

	//Reconectarse (crash, relog) le da al jugador un entityId nuevo — Minecraft nunca reutiliza el viejo.
	//Sin esto, su puesto en order quedaba huérfano para siempre: auto-skip perpetuo (ver beginTurn) y
	//tryAct(nuevoEntity) nunca coincidía con el id guardado, bloqueándolo de actuar por el resto del
	//encuentro. Se llama desde SheetLoader.clientJoinedServer en cada join; no hace nada si no hay
	//combate activo o si el jugador no tenía puesto en order.
	public static void reconcilePlayerEntity(ServerPlayer player) {
		if (!active) return;
		String uuid = player.getStringUUID();
		for (int i = 0; i < order.size(); i++) {
			TurnEntry old = order.get(i);
			if (!uuid.equals(old.playerUuid()) || old.entityId() == player.getId()) continue;

			int oldId = old.entityId();
			int newId = player.getId();
			order.set(i, new TurnEntry(newId, old.name(), old.isMonster(), old.playerUuid()));
			movementAnchors.rekey(oldId, newId);
			if (effects.containsKey(oldId)) effects.put(newId, effects.remove(oldId));
			if (actedThisTurn.remove(oldId)) actedThisTurn.add(newId);
			if (reactionUsed.remove(oldId)) reactionUsed.add(newId);
			opportunityAttacks.rekey(oldId, newId);
			return; //Un solo puesto por UUID en el orden, no hace falta seguir buscando.
		}
	}

	public static void applyEffect(Entity target, String name, String dice, int turns) {
		applyEffect(target, name, dice, turns, null);
	}

	/**
	 * @param source quien lo provoca, o {@code null} si no se sabe (el DM aplicándolo a mano). Solo importa
	 *               para hechizado y asustado, las dos condiciones de 5e cuyo efecto depende de quién es la
	 *               fuente — ver {@link Combatant#cannotAttack} y {@link Combatant#seesSourceOf}.
	 */
	public static void applyEffect(Entity target, String name, String dice, int turns, Entity source) {
		effects.computeIfAbsent(target.getId(), id -> new ArrayList<>()).add(new StatusEffect(name, dice, turns));
		//Si el nombre del efecto ES una condición de 5e ("derribado", "paralizado"...), además de contar los
		//turnos y hacer su daño se aplica de verdad como condición, con sus consecuencias mecánicas. Así todo
		//lo que ya sabía aplicar efectos —/dndturns effect, los ataques y hechizos de monstruo, los hechizos
		//de jugador— empieza a producir condiciones reales sin un comando nuevo ni un campo nuevo en el JSON.
		//Un nombre libre ("fuego", "sangrado") sigue siendo exactamente lo que era: un temporizador de daño.
		Condition condition = Condition.fromLabel(name);
		if (condition == null) return;
		Combatant combatant = Combatant.of(target);
		if (combatant != null) combatant.addCondition(condition, source == null ? Combatant.NO_SOURCE : source.getId());
	}

	//Único camino para quitar un efecto ANTES de que expire solo por tickEffects — hasta ahora solo se
	//podía perder por expiración natural o por el mapa entero vaciándose en start()/end(). Lo usa
	//ConcentrationManager para revertir el efecto de un hechizo de concentración en cuanto se pierde la
	//concentración (falla la salvación de Constitución) — antes eso solo tiraba el dado y mandaba un
	//mensaje, sin deshacer nada de verdad. No-op si el efecto ya no está (ya expiró, ya se quitó, o el
	//combate ya terminó y effects está vacío).
	public static void removeEffect(ServerLevel level, int entityId, String name) {
		//La condición se quita aunque el efecto ya no estuviera en el mapa: ambos caminos se aplicaron juntos
		//en applyEffect, pero el temporizador vive en memoria y solo durante el combate, mientras que la
		//condición se persiste. Sin esto, terminar un combate dejaba paralizado a alguien para siempre.
		Condition condition = Condition.fromLabel(name);
		if (condition != null && level != null) {
			Entity target = level.getEntity(entityId);
			Combatant combatant = target == null ? null : Combatant.of(target);
			if (combatant != null) combatant.removeCondition(condition);
		}

		List<StatusEffect> current = effects.get(entityId);
		if (current == null) return;
		List<StatusEffect> remaining = current.stream().filter(e -> !e.name().equals(name)).toList();
		if (remaining.isEmpty()) effects.remove(entityId);
		else effects.put(entityId, remaining);
	}

	//Público: para que un rasgo con duración (Furia del bárbaro, etc.) cuente en asaltos completos en vez
	//de ticks reales mientras el modo turnos esté activo — ver BarbarianRageManager. Si el modo turnos
	//termina antes de que pasen los asaltos, se dispara igual (ver end()): no se queda colgado para siempre.
	public static void onRoundsPass(int rounds, Runnable action) {
		pendingRoundCallbacks.add(new PendingRoundCallback(rounds, action));
	}

	private static void fireDueRoundCallbacks() {
		List<PendingRoundCallback> remaining = new ArrayList<>();
		List<Runnable> due = new ArrayList<>();
		for (PendingRoundCallback pending : pendingRoundCallbacks) {
			int left = pending.roundsRemaining() - 1;
			if (left <= 0) due.add(pending.action());
			else remaining.add(new PendingRoundCallback(left, pending.action()));
		}
		pendingRoundCallbacks.clear();
		pendingRoundCallbacks.addAll(remaining);
		due.forEach(Runnable::run);
	}

	private static void advance(ServerLevel level) {
		turnToken++; //Cualquier auto-avance encolado antes de este avance real queda invalidado.
		TurnEntry finishing = current();
		if (finishing != null) freeze(level, finishing); //Se ancla donde termine su turno.
		currentIndex++;
		if (currentIndex >= order.size()) {
			currentIndex = 0;
			round++;
			fireDueRoundCallbacks();
		}
		beginTurn(level);
	}

	private static void freeze(ServerLevel level, TurnEntry entry) {
		Entity entity = level.getEntity(entry.entityId());
		if (entity != null) {
			movementAnchors.pin(level, entry.entityId(), entity.position());
			if (entry.isMonster() && MonsterRegistry.statBlockOf(entity) == null) setCompatMobActive(entity, false);
		}
		clearGlow(level, entry);
	}

	//Ayuda visual (sección "para jugadores nuevos"): a quien tiene el turno se lo marca con el efecto
	//vanilla Brillo (visible a través de paredes), sin depender de leer el chat para saber a quién le
	//toca. GLOWING no hace nada más (no es un buff de combate real), así que es seguro aplicarlo/quitarlo
	//sin tocar ninguna otra mecánica.
	private static void glow(Entity entity) {
		if (entity instanceof LivingEntity living) living.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30 * 20 * 60, 0, false, false));
	}

	private static void clearGlow(ServerLevel level, TurnEntry entry) {
		if (entry == null) return;
		Entity entity = level.getEntity(entry.entityId());
		if (entity instanceof LivingEntity living) living.removeEffect(MobEffects.GLOWING);
	}

	private static void beginTurn(ServerLevel level) {
		if (allEnemiesDefeated(level)) {
			broadcast(level, Component.translatable("chat.dndsheets.turn.all_enemies_defeated").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			end(level);
			return;
		}

		TurnEntry entry = current();
		if (entry == null) return;
		actedThisTurn.remove(entry.entityId()); //Turno nuevo, acción nueva disponible.
		reactionUsed.remove(entry.entityId()); //Turno nuevo, reacción nueva disponible (regla real de 5e).
		movementAnchors.release(entry.entityId()); //A quien le toca ahora, se le suelta el ancla.

		Entity entity = level.getEntity(entry.entityId());
		if (entity == null || !entity.isAlive()) {
			//Ya no puede actuar (murió, se desconectó...): nadie va a escribir /dndturns next por él, así
			//que se salta su turno solo en vez de dejar el encuentro colgado para siempre.
			scheduleAutoAdvance(level, entry.entityId());
			return;
		}

		tickEffects(level, entity, entry);
		if (!entity.isAlive()) { //El propio efecto de estado (veneno...) pudo haberlo matado recién.
			scheduleAutoAdvance(level, entry.entityId());
			return;
		}

		glow(entity);
		broadcast(level, Component.translatable("chat.dndsheets.turn.round_header", round).withStyle(ChatFormatting.AQUA)
			.append(Component.literal(entry.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

		if (entity instanceof ServerPlayer serverPlayer) {
			CombatFx.actionBar(serverPlayer, Component.translatable("chat.dndsheets.turn.your_turn").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
			opportunityAttacks.seedReachState(level, serverPlayer, order);
			movementAnchors.beginMovementBudget(level, entry.entityId(), serverPlayer.position());
			broadcastTurnState(level);
		} else if (MonsterRegistry.statBlockOf(entity) != null) {
			//Sin DM en directo, un monstruo no puede esperar a que alguien le clique con la Vara de DM:
			//actúa solo en cuanto le toca (ver MonsterActionManager.autoAct). autoAct siempre llega a
			//tryAct (block/isAlive ya se comprobaron arriba), que ya manda su propio broadcastTurnState —
			//no hace falta uno más acá, sería el mismo paquete duplicado.
			MonsterActionManager.autoAct(level, entity);
		} else {
			//Mob de compatibilidad (Enemy de otro mod, o cualquier hostil vanilla suelto que entró por
			//TurnManager.isMonster) sin bloque de estadísticas propio: no hay ataque/daño 5e que resolver
			//como con los propios, así que se le devuelve su IA vanilla real para este turno (apagada de
			//nuevo en freeze() en cuanto termine) y se le da un presupuesto de movimiento igual que a un
			//jugador. Su turno se consume solo en cuanto ataca de verdad (onMobTurnAttack) o agota ese
			//presupuesto sin llegar a nadie (onMobTick) — nunca se queda esperando un tryAct que nadie iba
			//a llamar por él, y por si ninguna de las dos pasa nunca (el slime más pequeño, p.ej., jamás hace
			//daño al tocar por diseño vanilla — ver mobTurnStartTick), un límite de tiempo se lo corta igual.
			setCompatMobActive(entity, true);
			movementAnchors.beginMovementBudget(level, entry.entityId(), entity.position());
			mobTurnStartTick.put(entry.entityId(), level.getGameTime());
			broadcastTurnState(level);
		}
	}

	//Fin automático: si el encuentro arrancó con al menos un monstruo y ya no queda ninguno vivo (muerto o
	//borrado del mundo), se acabó solo — nadie tiene que escribir /dndturns end. No cuenta jugadores (que
	//un jugador llegue a 0 PG no termina el combate, ver DeathSaveManager) ni encuentros que arrancaron sin
	//monstruos (modo turnos usado para otra cosa, p.ej. una escena sin combate real).
	private static boolean allEnemiesDefeated(ServerLevel level) {
		boolean hadMonster = false;
		for (TurnEntry entry : order) {
			if (!entry.isMonster()) continue;
			hadMonster = true;
			if (confirmedDefeated.contains(entry.entityId())) continue; //Muerto/borrado de verdad, confirmado.
			Entity entity = level.getEntity(entry.entityId());
			//entity==null sin confirmación de arriba puede ser un chunk descargado, no una muerte: se asume
			//que sigue en pie para no terminar el combate de más (ver markDefeated).
			if (entity == null || entity.isAlive()) return false;
		}
		return hadMonster;
	}

	//Simétrico a allEnemiesDefeated: si TODOS los jugadores del encuentro murieron de verdad, se acaba
	//solo — sin esto, morir dejaba a cualquier mob de compatibilidad congelado (NoAI) para siempre, sin
	//nadie con permisos para correr /dndturns end si se jugaba sin DM en directo. Solo cuenta muerte real
	//(ver onPlayerRealDeath), NO estar caído con salvaciones pendientes (la partida sigue mientras alguien
	//pueda reanimar) ni desconexión (reconcilePlayerEntity ya asume que puede volver).
	private static boolean allPlayersDefeated() {
		boolean hadPlayer = false;
		for (TurnEntry entry : order) {
			if (entry.isMonster()) continue;
			hadPlayer = true;
			if (!confirmedDefeated.contains(entry.entityId())) return false;
		}
		return hadPlayer;
	}

	//Público: llamado por DeathSaveManager.killForReal justo donde un jugador muere de verdad (3 fallos de
	//salvación, o "Dejarse morir"). Si era el último con vida del encuentro, lo termina ya mismo —
	//restaura la IA de cualquier mob de compatibilidad que hubiera quedado congelado (ver end()).
	public static void onPlayerRealDeath(ServerLevel level, ServerPlayer player) {
		if (!active) return;
		markDefeated(player.getId());
		if (allPlayersDefeated()) {
			broadcast(level, Component.translatable("chat.dndsheets.turn.all_players_defeated").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
			end(level);
		}
	}

	//Servidor -> todos los clientes: estado actual del modo turnos, para el HUD (ver client.TurnHudOverlay).
	//Se manda cada vez que algo visible cambia (arranca, avanza, alguien gasta/deshace su acción) — ningún
	//cliente tiene que pedirlo, siempre llega solo.
	private static void broadcastTurnState(ServerLevel level) {
		TurnEntry entry = current();
		String name = entry != null ? entry.name() : "";
		int entityId = entry != null ? entry.entityId() : -1;
		boolean actioned = entry != null && actedThisTurn.contains(entry.entityId());
		Vec3 origin = entry != null ? movementAnchors.originOf(entry.entityId()) : Vec3.ZERO;
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.ALL.noArg(),
			new TurnStateMessage(active, round, name, entityId, actioned, origin.x, origin.y, origin.z));
	}

	private static void tickEffects(ServerLevel level, Entity entity, TurnEntry entry) {
		List<StatusEffect> active_ = effects.get(entry.entityId());
		if (active_ == null || active_.isEmpty()) return;

		List<StatusEffect> remaining = new ArrayList<>();
		for (StatusEffect effect : active_) {
			DiceManager.RollOutcome outcome = DiceManager.roll(new JsonObject(), effect.damageDice());
			//amount > 0, no solo "se pudo tirar": una condición pura (Inmovilizar Persona, Dormir) se guarda
			//con dados "0" porque no hace daño, y sin este filtro anunciaba un tick de 0 puntos cada turno
			//que dura — ruido en el chat justo cuando más lleno está.
			if (outcome.result() != null && outcome.result().getValue() > 0) {
				int amount = outcome.result().getValue();
				SpellCastManager.applyDamage(entity, amount, effect.name());
				broadcast(level, Component.translatable("chat.dndsheets.turn.effect_tick", entry.name(), effect.name(), outcome.formatted()).withStyle(ChatFormatting.DARK_GREEN));
			}
			int left = effect.remainingTurns() - 1;
			if (left > 0) {
				remaining.add(new StatusEffect(effect.name(), effect.damageDice(), left));
			} else {
				//Se acabó el contador: si el efecto era una condición de verdad, se levanta también la
				//condición. Sin esto expiraba el temporizador pero el personaje se quedaba derribado o
				//paralizado para siempre, porque la condición se persiste y el temporizador no.
				Condition condition = Condition.fromLabel(effect.name());
				if (condition != null) {
					Combatant combatant = Combatant.of(entity);
					if (combatant != null) combatant.removeCondition(condition);
				}
				broadcast(level, Component.translatable("chat.dndsheets.turn.effect_ended", entry.name(), effect.name()).withStyle(ChatFormatting.GRAY));
			}
		}
		if (remaining.isEmpty()) effects.remove(entry.entityId());
		else effects.put(entry.entityId(), remaining);
	}

	//ponytail: un solo guardado global para todos los mutadores en vez de uno por encuentro — solo se
	//admite un encuentro de modo turnos a la vez. Si algún día hacen falta encuentros simultáneos, esto
	//tendría que volverse un mapa por encuentro.
	private static boolean debounce(ServerLevel level) {
		long now = level.getGameTime();
		if (lastActionTick == now) return true;
		lastActionTick = now;
		return false;
	}

	//Antes era server-wide: los anuncios de turno ("turno de X", ronda N, fin del combate...) los veía
	//cualquiera conectado, no solo quien estuviera en o cerca de este encuentro — mismo problema que
	//ChatFeedback.broadcast, mismo criterio de solución (radio desde donde arrancó el combate, no todo el
	//servidor). Sin zona conocida (no debería pasar con combate activo, pero por si acaso) cae a
	//server-wide antes que perder el aviso.
	private static void broadcast(ServerLevel level, Component message) {
		if (combatOrigin == null) {
			level.getServer().getPlayerList().broadcastSystemMessage(message, false);
			return;
		}
		double radiusSq = combatRadius * combatRadius;
		for (ServerPlayer player : level.players()) {
			if (player.position().distanceToSqr(combatOrigin) <= radiusSq) player.sendSystemMessage(message);
		}
	}

	//Bloqueo de movimiento: a quien tiene una posición anclada (no le toca el turno) se le devuelve ahí en
	//cuanto se aleja, y se le avisa por qué. Sin ancla (le toca a él, o modo turnos apagado) no hace nada.
	@SubscribeEvent
	public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
		if (event.phase != TickEvent.Phase.END || !active) return;
		if (!(event.player instanceof ServerPlayer player)) return;

		if (movementAnchors.isAnchorHandledThisTick(player)) return;

		TurnEntry currentEntry = current();
		if (currentEntry != null && currentEntry.entityId() == player.getId() && player.level() instanceof ServerLevel level) {
			opportunityAttacks.checkOpportunityAttacks(level, player, order);
			movementAnchors.enforceMovementBudget(player);
		}
	}
}
