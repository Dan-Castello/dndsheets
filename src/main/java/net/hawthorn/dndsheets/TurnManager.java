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
	public record Combatant(int entityId, String name, boolean isMonster, String playerUuid) {}
	public record StatusEffect(String name, String damageDice, int remainingTurns) {}

	private static final List<Combatant> order = new ArrayList<>();
	private static int currentIndex = -1;
	private static int round = 0;
	private static boolean active = false;
	private static long lastActionTick = -1;

	//Generación del turno actual: sube cada vez que el turno de verdad avanza (advance) o que se deshace
	//una acción (undoAction). scheduleAutoAdvance captura el valor vigente al encolar el auto-avance y solo
	//lo ejecuta si nadie lo cambió mientras tanto — sin esto, deshacer una acción y volver a actuar (p.ej.
	//con el ítem "Deshacer Turno") podía disparar el auto-avance viejo Y el nuevo, dando una acción extra
	//gratis, y un /dndturns next manual justo antes del auto-avance podía hacer avanzar la ronda dos veces.
	private static int turnToken = 0;

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
		if (MonsterRegistry.statBlockOf(event.getEntity()) == null) return;
		markDefeated(event.getEntity().getId());
		if (event.getEntity().level() instanceof ServerLevel level) checkAllEnemiesDefeated(level);
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

	private static Combatant current() {
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
		if (!active) return true;
		Combatant currentCombatant = current();
		if (currentCombatant == null || currentCombatant.entityId() != actor.getId()) return false;
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
		Combatant currentCombatant = current();
		boolean isCurrentActor = currentCombatant != null && currentCombatant.entityId() == actor.getId();
		Component reason = isCurrentActor
			? Component.translatable("chat.dndsheets.turn.already_acted")
			: Component.translatable("chat.dndsheets.turn.not_your_turn",
				currentCombatant != null ? currentCombatant.name() : Component.translatable("chat.dndsheets.turn.other_combatant"));
		player.sendSystemMessage(reason.copy().withStyle(ChatFormatting.RED));
	}

	//Un tick después de gastar la acción, si sigue siendo el mismo combatiente (nadie avanzó a mano de
	//por medio), pasa el turno solo. El tick de margen deja que se vea el resultado de la acción antes del
	//anuncio de ronda siguiente, y evita reentrar en advance() en medio de la resolución del ataque/hechizo
	//que todavía está corriendo cuando tryAct devuelve true.
	private static void scheduleAutoAdvance(ServerLevel level, int entityId) {
		int scheduledToken = turnToken;
		DndsheetsMod.queueServerWork(1, () -> {
			Combatant stillCurrent = current();
			if (!active || stillCurrent == null || stillCurrent.entityId() != entityId || turnToken != scheduledToken) return;
			advance(level);
		});
	}

	//Usado por los ítems de comodidad (TurnItemManager): solo quien tiene el turno puede usarlos.
	public static boolean isCurrentActor(Entity actor) {
		Combatant currentCombatant = current();
		return active && currentCombatant != null && currentCombatant.entityId() == actor.getId();
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
		AABB box = new AABB(pos.x - radius, pos.y - radius, pos.z - radius, pos.x + radius, pos.y + radius, pos.z + radius);

		record Rolled(int entityId, String name, int score, boolean isMonster, String playerUuid) {}
		List<Rolled> rolled = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, box, e -> e instanceof Player || MonsterRegistry.monsterIdOf(e) != null)) {
			String playerUuid = entity instanceof Player player ? player.getStringUUID() : null;
			rolled.add(new Rolled(entity.getId(), nameOf(entity), rollInitiative(entity), MonsterRegistry.monsterIdOf(entity) != null, playerUuid));
		}
		rolled.sort((a, b) -> b.score() - a.score());

		List<Combatant> combatants = new ArrayList<>();
		for (Rolled r : rolled) combatants.add(new Combatant(r.entityId(), r.name() + " (" + r.score() + ")", r.isMonster(), r.playerUuid()));

		if (combatants.isEmpty()) return 0;

		start(level, combatants);
		return combatants.size();
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

	public static void start(ServerLevel level, List<Combatant> rolledOrder) {
		if (debounce(level)) return;

		//Reinicio en caliente (un encuentro ya activo, p.ej. /dndturns start disparado dos veces o un golpe
		//que arranca un segundo combate por error): sin esto, el combatiente que tenía el turno del
		//encuentro VIEJO se quedaba con el efecto Brillo pegado para siempre (nada más lo limpiaba), y como
		//turnToken no subía, un auto-avance ya encolado del encuentro viejo podía colarse y saltarse el
		//primer turno del nuevo.
		if (active) {
			clearGlow(level, current());
			turnToken++;
		}

		order.clear();
		effects.clear();
		actedThisTurn.clear();
		reactionUsed.clear();
		opportunityAttacks.clear();
		movementAnchors.clear();
		confirmedDefeated.clear();
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
		broadcast(level, Component.translatable("chat.dndsheets.turn.ended").withStyle(ChatFormatting.GRAY));
		order.clear();
		effects.clear();
		actedThisTurn.clear();
		reactionUsed.clear();
		opportunityAttacks.clear();
		movementAnchors.clear();
		confirmedDefeated.clear();
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
	//de todo el orden) — llamado desde MonsterRegistry.spawnAt, el único punto por el que pasan los cuatro
	//caminos de invocación (comando, panel de DM, carta, NPC genérico).
	public static void addLateMonster(ServerLevel level, Entity monster, String displayName) {
		if (!active) return;
		order.add(currentIndex + 1, new Combatant(monster.getId(), displayName, true, null));
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
		for (Combatant combatant : order) {
			if (combatant.entityId() == player.getId()) return;
		}
		String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(player.getStringUUID()), player);
		Combatant newCombatant = new Combatant(player.getId(), name, false, player.getStringUUID());
		order.add(currentIndex + 1, newCombatant);
		freeze(level, newCombatant);
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
			Combatant old = order.get(i);
			if (!uuid.equals(old.playerUuid()) || old.entityId() == player.getId()) continue;

			int oldId = old.entityId();
			int newId = player.getId();
			order.set(i, new Combatant(newId, old.name(), old.isMonster(), old.playerUuid()));
			movementAnchors.rekey(oldId, newId);
			if (effects.containsKey(oldId)) effects.put(newId, effects.remove(oldId));
			if (actedThisTurn.remove(oldId)) actedThisTurn.add(newId);
			if (reactionUsed.remove(oldId)) reactionUsed.add(newId);
			opportunityAttacks.rekey(oldId, newId);
			return; //Un solo puesto por UUID en el orden, no hace falta seguir buscando.
		}
	}

	public static void applyEffect(Entity target, String name, String dice, int turns) {
		effects.computeIfAbsent(target.getId(), id -> new ArrayList<>()).add(new StatusEffect(name, dice, turns));
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
		Combatant finishing = current();
		if (finishing != null) freeze(level, finishing); //Se ancla donde termine su turno.
		currentIndex++;
		if (currentIndex >= order.size()) {
			currentIndex = 0;
			round++;
			fireDueRoundCallbacks();
		}
		beginTurn(level);
	}

	private static void freeze(ServerLevel level, Combatant combatant) {
		Entity entity = level.getEntity(combatant.entityId());
		if (entity != null) movementAnchors.pin(level, combatant.entityId(), entity.position());
		clearGlow(level, combatant);
	}

	//Ayuda visual (sección "para jugadores nuevos"): a quien tiene el turno se lo marca con el efecto
	//vanilla Brillo (visible a través de paredes), sin depender de leer el chat para saber a quién le
	//toca. GLOWING no hace nada más (no es un buff de combate real), así que es seguro aplicarlo/quitarlo
	//sin tocar ninguna otra mecánica.
	private static void glow(Entity entity) {
		if (entity instanceof LivingEntity living) living.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30 * 20 * 60, 0, false, false));
	}

	private static void clearGlow(ServerLevel level, Combatant combatant) {
		if (combatant == null) return;
		Entity entity = level.getEntity(combatant.entityId());
		if (entity instanceof LivingEntity living) living.removeEffect(MobEffects.GLOWING);
	}

	private static void beginTurn(ServerLevel level) {
		if (allEnemiesDefeated(level)) {
			broadcast(level, Component.translatable("chat.dndsheets.turn.all_enemies_defeated").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			end(level);
			return;
		}

		Combatant combatant = current();
		if (combatant == null) return;
		actedThisTurn.remove(combatant.entityId()); //Turno nuevo, acción nueva disponible.
		reactionUsed.remove(combatant.entityId()); //Turno nuevo, reacción nueva disponible (regla real de 5e).
		movementAnchors.release(combatant.entityId()); //A quien le toca ahora, se le suelta el ancla.

		Entity entity = level.getEntity(combatant.entityId());
		if (entity == null || !entity.isAlive()) {
			//Ya no puede actuar (murió, se desconectó...): nadie va a escribir /dndturns next por él, así
			//que se salta su turno solo en vez de dejar el encuentro colgado para siempre.
			scheduleAutoAdvance(level, combatant.entityId());
			return;
		}

		tickEffects(level, entity, combatant);
		if (!entity.isAlive()) { //El propio efecto de estado (veneno...) pudo haberlo matado recién.
			scheduleAutoAdvance(level, combatant.entityId());
			return;
		}

		glow(entity);
		broadcast(level, Component.translatable("chat.dndsheets.turn.round_header", round).withStyle(ChatFormatting.AQUA)
			.append(Component.literal(combatant.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

		if (entity instanceof ServerPlayer serverPlayer) {
			CombatFx.actionBar(serverPlayer, Component.translatable("chat.dndsheets.turn.your_turn").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
			opportunityAttacks.seedReachState(level, serverPlayer, order);
			movementAnchors.beginMovementBudget(level, combatant.entityId(), serverPlayer.position());
			broadcastTurnState(level);
		} else if (MonsterRegistry.statBlockOf(entity) != null) {
			//Sin DM en directo, un monstruo no puede esperar a que alguien le clique con la Vara de DM:
			//actúa solo en cuanto le toca (ver MonsterActionManager.autoAct). autoAct siempre llega a
			//tryAct (block/isAlive ya se comprobaron arriba), que ya manda su propio broadcastTurnState —
			//no hace falta uno más acá, sería el mismo paquete duplicado.
			MonsterActionManager.autoAct(level, entity);
		}
	}

	//Fin automático: si el encuentro arrancó con al menos un monstruo y ya no queda ninguno vivo (muerto o
	//borrado del mundo), se acabó solo — nadie tiene que escribir /dndturns end. No cuenta jugadores (que
	//un jugador llegue a 0 PG no termina el combate, ver DeathSaveManager) ni encuentros que arrancaron sin
	//monstruos (modo turnos usado para otra cosa, p.ej. una escena sin combate real).
	private static boolean allEnemiesDefeated(ServerLevel level) {
		boolean hadMonster = false;
		for (Combatant combatant : order) {
			if (!combatant.isMonster()) continue;
			hadMonster = true;
			if (confirmedDefeated.contains(combatant.entityId())) continue; //Muerto/borrado de verdad, confirmado.
			Entity entity = level.getEntity(combatant.entityId());
			//entity==null sin confirmación de arriba puede ser un chunk descargado, no una muerte: se asume
			//que sigue en pie para no terminar el combate de más (ver markDefeated).
			if (entity == null || entity.isAlive()) return false;
		}
		return hadMonster;
	}

	//Servidor -> todos los clientes: estado actual del modo turnos, para el HUD (ver client.TurnHudOverlay).
	//Se manda cada vez que algo visible cambia (arranca, avanza, alguien gasta/deshace su acción) — ningún
	//cliente tiene que pedirlo, siempre llega solo.
	private static void broadcastTurnState(ServerLevel level) {
		Combatant combatant = current();
		String name = combatant != null ? combatant.name() : "";
		int entityId = combatant != null ? combatant.entityId() : -1;
		boolean actioned = combatant != null && actedThisTurn.contains(combatant.entityId());
		Vec3 origin = combatant != null ? movementAnchors.originOf(combatant.entityId()) : Vec3.ZERO;
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.ALL.noArg(),
			new TurnStateMessage(active, round, name, entityId, actioned, origin.x, origin.y, origin.z));
	}

	private static void tickEffects(ServerLevel level, Entity entity, Combatant combatant) {
		List<StatusEffect> active_ = effects.get(combatant.entityId());
		if (active_ == null || active_.isEmpty()) return;

		List<StatusEffect> remaining = new ArrayList<>();
		for (StatusEffect effect : active_) {
			DiceManager.RollOutcome outcome = DiceManager.roll(new JsonObject(), effect.damageDice());
			if (outcome.result() != null) {
				int amount = outcome.result().getValue();
				SpellCastManager.applyDamage(entity, amount, effect.name());
				broadcast(level, Component.translatable("chat.dndsheets.turn.effect_tick", combatant.name(), effect.name(), outcome.formatted()).withStyle(ChatFormatting.DARK_GREEN));
			}
			int left = effect.remainingTurns() - 1;
			if (left > 0) {
				remaining.add(new StatusEffect(effect.name(), effect.damageDice(), left));
			} else {
				broadcast(level, Component.translatable("chat.dndsheets.turn.effect_ended", combatant.name(), effect.name()).withStyle(ChatFormatting.GRAY));
			}
		}
		if (remaining.isEmpty()) effects.remove(combatant.entityId());
		else effects.put(combatant.entityId(), remaining);
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

	private static void broadcast(ServerLevel level, Component message) {
		level.getServer().getPlayerList().broadcastSystemMessage(message, false);
	}

	//Bloqueo de movimiento: a quien tiene una posición anclada (no le toca el turno) se le devuelve ahí en
	//cuanto se aleja, y se le avisa por qué. Sin ancla (le toca a él, o modo turnos apagado) no hace nada.
	@SubscribeEvent
	public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
		if (event.phase != TickEvent.Phase.END || !active) return;
		if (!(event.player instanceof ServerPlayer player)) return;

		if (movementAnchors.isAnchorHandledThisTick(player)) return;

		Combatant currentCombatant = current();
		if (currentCombatant != null && currentCombatant.entityId() == player.getId() && player.level() instanceof ServerLevel level) {
			opportunityAttacks.checkOpportunityAttacks(level, player, order);
			movementAnchors.enforceMovementBudget(player);
		}
	}
}
