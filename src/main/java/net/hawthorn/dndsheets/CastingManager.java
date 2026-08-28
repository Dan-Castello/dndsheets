package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p><b>Conjuros que tardan en salir.</b> Hasta ahora todo hechizo se resolvía en el mismo tick en que se
 * pedía: el chat cantaba el resultado antes de que la primera partícula llegara a nadie. Con un
 * {@code castTicks} por encima de cero (ver {@code SpellRegistry.Spell#castTicksAt} y {@link Config}), el
 * conjuro queda aquí en marcha durante ese rato, cargándose visiblemente en las manos del lanzador, y se
 * resuelve al terminar.</p>
 *
 * <p><b>El coste ya está pagado cuando esto empieza.</b> {@code SpellCastManager.prepare} cobró la acción
 * del turno y el espacio de conjuro antes de llegar aquí, y eso es deliberado: en 5e la acción se gasta al
 * EMPEZAR a conjurar, y un conjuro interrumpido a media faena se pierde con su espacio. Es la misma regla
 * que ya aplicaba el Contrahechizo, donde el espacio también se gasta aunque el hechizo no llegue a surtir
 * efecto.</p>
 *
 * <p>Tres cosas lo interrumpen, y las tres son la regla de 5e para un conjuro de un asalto o más: recibir
 * daño y fallar la salvación de Constitución (misma CD que la concentración, {@code máx(10, daño/2)} —
 * comparten fórmula a propósito, es la misma regla escrita una vez), quedar incapacitado o morir, y
 * moverse del sitio.</p>
 *
 * <p>El estado vive en RAM y no en la hoja, a diferencia de la concentración: un conjuro a medio conjurar
 * no debe sobrevivir a un reinicio del servidor. Al cliente solo se le manda un parche por campo —el mismo
 * camino que ya usan la concentración y la ventaja pendiente, sin red nueva— para que la animación de
 * primera persona sepa cuándo empezar y cuándo parar (ver {@code client.SpellCastAnimator}).</p>
 */
@Mod.EventBusSubscriber
public class CastingManager {
	/** Mutable a propósito: {@code ticksLeft} baja en cada tick, y un record obligaría a reinsertar en el mapa. */
	private static final class Casting {
		private final SpellCastManager.CastRequest request;
		private final int totalTicks;
		private final Vec3 origin;
		private int ticksLeft;

		private Casting(SpellCastManager.CastRequest request, int totalTicks, Vec3 origin) {
			this.request = request;
			this.totalTicks = totalTicks;
			this.origin = origin;
			this.ticksLeft = totalTicks;
		}

		private float progress() {
			return 1.0f - (float) ticksLeft / totalTicks;
		}
	}

	private static final Map<UUID, Casting> casting = new HashMap<>();

	//Un bloque de margen: la idea es "te quedaste quieto conjurando", no "no respiraste". Sin margen, el
	//retroceso de un golpe o el propio paso de la caminata cancelarían el conjuro por su cuenta.
	private static final double MAX_MOVE = 1.0;

	/**
	 * <p>Arranca un lanzamiento diferido. El {@code CastRequest} ya trae el objetivo apuntado y el conjuro
	 * subido de nivel: no se vuelve a apuntar al resolver, porque eso dejaría que un conjuro corrigiera su
	 * puntería solo con girar la cámara mientras se conjura.</p>
	 */
	static void begin(ServerPlayer caster, SpellCastManager.CastRequest request, int castTicks) {
		//Si ya había uno a medias (dos peticiones seguidas), gana el nuevo: el viejo ya cobró lo suyo, así
		//que abandonarlo es lo mismo que interrumpirlo, sin devolver nada.
		casting.remove(caster.getUUID());

		casting.put(caster.getUUID(), new Casting(request, castTicks, caster.position()));
		//El turno NO puede pasar mientras se conjura: TurnManager.tryAct ya encoló su auto-avance para el
		//tick siguiente, y sin esto el conjuro resolvería con el turno de otro ya empezado.
		TurnManager.holdAutoAdvance();
		notifyClient(caster, request.spell().name(), castTicks);
	}

	//Un tick de servidor: baja el contador de cada lanzamiento vivo, pinta la carga y resuelve los que
	//llegan a cero. Fuera de un lanzamiento activo no corre nada (el mapa está vacío casi siempre).
	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END || casting.isEmpty()) return;

		//Se recogen primero y se actúa después: resolver un conjuro puede tocar este mismo mapa (matar al
		//lanzador, arrancar otro conjuro), y eso reventaría un iterador abierto.
		List<UUID> finished = new ArrayList<>();
		List<UUID> broken = new ArrayList<>();
		for (Map.Entry<UUID, Casting> entry : casting.entrySet()) {
			Casting current = entry.getValue();
			ServerPlayer caster = casterOf(entry.getKey());
			if (caster == null) continue;

			//Incapacitado, muerto o movido del sitio: se pierde sin llegar a tirar ningún dado.
			Combatant self = Combatant.of(caster);
			if (!caster.isAlive() || (self != null && self.cannotAct())
					|| caster.position().distanceToSqr(current.origin) > MAX_MOVE * MAX_MOVE) {
				broken.add(entry.getKey());
				continue;
			}

			CombatFx.spellCharge(caster, current.request.spell().school(), current.progress());
			CombatFx.actionBar(caster, Component.translatable("chat.dndsheets.spell.casting",
				current.request.spell().name(), progressBar(current.progress())).withStyle(ChatFormatting.AQUA));

			if (--current.ticksLeft <= 0) finished.add(entry.getKey());
		}

		for (UUID id : broken) fail(id, "chat.dndsheets.spell.cast_interrupted_moved", null, 0);
		for (UUID id : finished) {
			Casting done = casting.remove(id);
			ServerPlayer caster = casterOf(id);
			if (done == null || caster == null) continue;
			notifyClient(caster, null, 0);
			//Se le devuelve el auto-avance al turno ANTES de resolver: el efecto del conjuro puede empezar
			//un combate, matar al lanzador o cambiar de quién es el turno, y todo eso tiene que verse sobre
			//el orden ya restablecido, no sobre uno retenido.
			releaseTurn(caster);
			SpellCastManager.resolve(caster, done.request);
		}
	}

	/**
	 * <p>Daño recibido mientras se conjura: salvación de Constitución contra {@code máx(10, daño/2)} o el
	 * conjuro se pierde con su espacio. Llamado desde {@link ConcentrationManager#onDamageTaken}, que es el
	 * punto por el que ya pasan TODOS los caminos de daño del mod — engancharse ahí es una llamada en vez
	 * de cuatro, y ninguna que se pueda olvidar al añadir un camino de daño nuevo.</p>
	 */
	public static void onDamageTaken(ServerPlayer player, int damage) {
		Casting current = casting.get(player.getUUID());
		if (current == null || damage <= 0) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		int dc = Math.max(10, damage / 2);
		DiceManager.RollOutcome saveRoll = sheet != null ? DiceManager.roll(sheet, "1d20 + $con") : DiceManager.roll(new JsonObject(), "1d20");
		if (saveRoll.result() != null && saveRoll.result().getValue() >= dc) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.spell.cast_kept",
				SheetLoader.characterNameOf(sheet, player), current.request.spell().name(), dc, saveRoll.formatted())
				.withStyle(ChatFormatting.GRAY));
			return;
		}
		fail(player.getUUID(), "chat.dndsheets.spell.cast_interrupted", saveRoll.formatted(), dc);
	}

	/**
	 * <p>Cambio de personaje o desconexión: lo que había a medias se pierde, sin mensaje. Llamado desde
	 * {@code SheetLoader}, donde ya está centralizada la limpieza de todos los estados vivos indexados por
	 * jugador (concentración, furia, forma salvaje, marca del cazador) en vez de repartir un suscriptor
	 * idéntico por cada uno.</p>
	 *
	 * <p>Al desconectar solo hay que soltar la memoria: avisar al cliente de alguien que ya se fue no llega
	 * a ninguna parte, y devolverle el turno a un jugador que ya no está tampoco.</p>
	 */
	public static void clearFor(ServerPlayer player) {
		if (casting.remove(player.getUUID()) == null || player.hasDisconnected()) return;
		notifyClient(player, null, 0);
		releaseTurn(player);
	}

	private static void fail(UUID id, String messageKey, String rollText, int dc) {
		Casting lost = casting.remove(id);
		ServerPlayer caster = casterOf(id);
		if (lost == null || caster == null) return;

		notifyClient(caster, null, 0);
		releaseTurn(caster);
		String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(caster.getStringUUID()), caster);
		Component message = rollText == null
			? Component.translatable(messageKey, name, lost.request.spell().name())
			: Component.translatable(messageKey, name, lost.request.spell().name(), dc, rollText);
		ChatFeedback.broadcast(caster, message.copy().withStyle(ChatFormatting.RED));
	}

	//El turno estaba retenido desde begin(): al acabar el lanzamiento —bien o mal— vuelve a comportarse
	//como cualquier otra acción gastada y pasa solo.
	private static void releaseTurn(ServerPlayer caster) {
		if (caster.level() instanceof ServerLevel level) TurnManager.resumeAutoAdvance(level, caster);
	}

	private static ServerPlayer casterOf(UUID id) {
		net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
		return server != null ? server.getPlayerList().getPlayer(id) : null;
	}

	//Mismo camino por el que ya viajan la concentración, el castigo armado y la ventaja pendiente (ver
	//ConcentrationManager.notifyClient): un parche por campo, cero mensajes de red nuevos. Viaja el tick de
	//FIN además de la duración, para que el cliente calcule el progreso él solo en vez de recibir un parche
	//de red por cada tick de lanzamiento.
	//
	//A diferencia de la concentración, esto NO escribe en el JsonObject de la hoja del servidor, solo manda
	//el parche: un conjuro a medio conjurar no debe sobrevivir a un reinicio, así que persistirlo sería
	//justo el error (y el self-test de la invariante 4 lo caza, con razón — la hoja que se toca se guarda).
	//El precio es que un envío de hoja COMPLETA a mitad de lanzamiento le borra el campo al cliente y le
	//corta la animación antes de tiempo; se cura solo al siguiente lanzamiento y no toca ninguna regla.
	private static void notifyClient(ServerPlayer caster, String spellName, int castTicks) {
		JsonObject patch = new JsonObject();
		if (spellName == null) {
			patch.add("castingSpell", com.google.gson.JsonNull.INSTANCE); //Null en un parche = borrar la clave.
			patch.add("castingTicks", com.google.gson.JsonNull.INSTANCE);
			patch.add("castingUntil", com.google.gson.JsonNull.INSTANCE);
		} else {
			patch.addProperty("castingSpell", spellName);
			patch.addProperty("castingTicks", castTicks);
			patch.addProperty("castingUntil", caster.level().getGameTime() + castTicks);
		}
		DndsheetsMod.sendSheetFieldUpdate(caster, patch);
	}

	//Diez casillas en ASCII puro: la barra de acción la pinta la fuente del juego, y un carácter de bloque
	//unicode sale como un cuadrito vacío en cuanto la fuente no lo trae. Los corchetes los pone el archivo
	//de idioma, no esto.
	private static String progressBar(float progress) {
		int filled = Math.max(0, Math.min(10, Math.round(progress * 10)));
		return "=".repeat(filled) + "-".repeat(10 - filled);
	}
}
