package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//Presupuesto de movimiento y anclaje de posición del modo turnos — extraído de TurnManager (ver
//AUDIT_REPORT_2026.md F3): quien NO tiene el turno queda anclado donde estaba; quien SÍ lo tiene solo
//puede alejarse hasta su "speed" de hoja antes de que se le devuelva a la última posición válida. Estado
//propio, sin tocar el orden de turnos — recibe lo que necesita como parámetro en vez de leer los campos
//de TurnManager directamente.
class MovementAnchorTracker {
	private record Pinned(ResourceKey<Level> dimension, Vec3 pos) {}

	//Posición donde debe quedarse quien no tiene el turno; sin entrada = libre para moverse (le toca a él).
	private final Map<Integer, Pinned> anchors = new HashMap<>();
	private static final double ANCHOR_TOLERANCE = 0.05;

	//Presupuesto de movimiento de quien SÍ tiene el turno: dónde estaba al empezarlo (origin) y la última
	//posición vista dentro de su alcance (lastGoodPos, a donde se le devuelve si se pasa).
	private final Map<Integer, Pinned> moveOrigin = new HashMap<>();
	private final Map<Integer, Pinned> lastGoodPos = new HashMap<>();
	private static final int DEFAULT_SPEED_FEET = 30;
	private static final double FEET_PER_BLOCK = 5.0;
	//speedBlocksFor corre 20 veces/seg mientras dura el turno de un jugador: el Pattern se cachea en vez
	//de recompilarse en cada tick.
	private static final Pattern SPEED_FEET_PATTERN = Pattern.compile("\\d+");

	//"speed" no está clampeado al guardarse (es texto libre tipo "30 ft (trepar 30 ft)", no un campo
	//puramente numérico como los ability scores) — el límite tiene que aplicarse aquí, el único lugar
	//donde ese texto se convierte en un número real que manda sobre el movimiento en combate. Sin esto,
	//un jugador podía escribir "99999 ft" y saltarse por completo el presupuesto de movimiento.
	private static final int MAX_SPEED_FEET = 500;

	void pin(ServerLevel level, int entityId, Vec3 pos) {
		anchors.put(entityId, new Pinned(level.dimension(), pos));
	}

	void release(int entityId) {
		anchors.remove(entityId);
	}

	void clear() {
		anchors.clear();
		moveOrigin.clear();
		lastGoodPos.clear();
	}

	void rekey(int oldId, int newId) {
		if (anchors.containsKey(oldId)) anchors.put(newId, anchors.remove(oldId));
		if (moveOrigin.containsKey(oldId)) moveOrigin.put(newId, moveOrigin.remove(oldId));
		if (lastGoodPos.containsKey(oldId)) lastGoodPos.put(newId, lastGoodPos.remove(oldId));
	}

	void beginMovementBudget(ServerLevel level, int entityId, Vec3 pos) {
		Pinned pinned = new Pinned(level.dimension(), pos);
		moveOrigin.put(entityId, pinned);
		lastGoodPos.put(entityId, pinned);
	}

	Vec3 originOf(int entityId) {
		Pinned pinned = moveOrigin.get(entityId);
		return pinned != null ? pinned.pos() : Vec3.ZERO;
	}

	/** @return true si el tick de este jugador ya quedó resuelto por el anclaje (el llamador debe cortar ahí). */
	boolean isAnchorHandledThisTick(ServerPlayer player) {
		Pinned anchor = anchors.get(player.getId());
		if (anchor == null) return false;

		if (player.level().dimension() != anchor.dimension()) {
			//Cambió de dimensión estando anclado (empujado a un portal, p.ej.): se suelta el ancla en vez
			//de comparar/teletransportar entre niveles distintos.
			anchors.remove(player.getId());
			return false;
		}

		//Solo se corrige el plano horizontal (X/Z); la Y se deja completamente libre. Antes se
		//comparaba/reponía la posición en 3D: si el turno terminaba con el jugador en el aire (p.ej.
		//saltando para forzar un crítico de Minecraft), quedaba anclado exactamente en esa altura para
		//siempre, con la gravedad peleando cada tick contra el teletransporte de vuelta — congelado en el
		//aire. Dejando Y sin tocar, la gravedad aterriza solo y el anclaje horizontal sigue impidiendo
		//caminar lejos.
		double dx = player.getX() - anchor.pos().x;
		double dz = player.getZ() - anchor.pos().z;
		if (dx * dx + dz * dz <= ANCHOR_TOLERANCE * ANCHOR_TOLERANCE) {
			return true;
		}

		player.teleportTo(anchor.pos().x, player.getY(), anchor.pos().z);
		Vec3 delta = player.getDeltaMovement();
		player.setDeltaMovement(0, delta.y, 0);
		CombatFx.actionBar(player, Component.translatable("chat.dndsheets.turn.cant_move").withStyle(ChatFormatting.RED));
		return true;
	}

	//Cuántos bloques puede moverse este turno: la "speed" de la hoja (texto libre, p.ej. "30 ft") convertida
	//a bloques a 5 pies por bloque (misma conversión de rejilla que usa el resto de VTTs de mesa). Sin campo
	//o sin número reconocible, cae a la velocidad estándar de 5e (30 pies = 6 bloques).
	private static double speedBlocksFor(JsonObject sheet) {
		int feet = DEFAULT_SPEED_FEET;
		if (sheet != null && sheet.has("speed")) {
			try {
				Matcher matcher = SPEED_FEET_PATTERN.matcher(sheet.get("speed").getAsString());
				if (matcher.find()) {
					feet = Integer.parseInt(matcher.group());
				}
			} catch (RuntimeException ignored) {
				//NumberFormatException del parseo, o UnsupportedOperationException si "speed" quedó como
				//un JsonObject/JsonArray en una hoja vieja: en ambos casos, cae a DEFAULT_SPEED_FEET.
			}
		}
		feet = Math.max(0, Math.min(MAX_SPEED_FEET, feet));
		return feet / FEET_PER_BLOCK;
	}

	//Conversión aproximada de la velocidad vanilla de un mob (atributo MOVEMENT_SPEED, una unidad interna
	//sin equivalencia exacta en bloques/turno por cómo funciona de verdad la física de movimiento de
	//Minecraft: fricción, terreno, salto...) a un presupuesto de bloques por turno: se escala contra la
	//velocidad base de un zombie (referencia común de "mob normal") como si esa velocidad representara los
	//30 pies/6 bloques estándar de 5e, con un rango razonable para no dar presupuestos absurdos a mobs muy
	//rápidos/lentos. ponytail: heurística, no una simulación real de física — si algún mob de un mod queda
	//claramente corto o largo de más, ajustar ZOMBIE_BASELINE_SPEED o el rango del clamp.
	private static final double ZOMBIE_BASELINE_SPEED = 0.23;
	private static final double MIN_MOB_SPEED_BLOCKS = 2.0;
	private static final double MAX_MOB_SPEED_BLOCKS = 12.0;

	static double speedBlocksForMob(Entity entity) {
		if (!(entity instanceof LivingEntity living)) return 6.0;
		AttributeInstance speedAttr = living.getAttribute(Attributes.MOVEMENT_SPEED);
		double raw = speedAttr != null ? speedAttr.getValue() : ZOMBIE_BASELINE_SPEED;
		double blocks = 6.0 * (raw / ZOMBIE_BASELINE_SPEED);
		return Math.max(MIN_MOB_SPEED_BLOCKS, Math.min(MAX_MOB_SPEED_BLOCKS, blocks));
	}

	//Bloqueo de movimiento de quien SÍ tiene el turno: en cuanto se aleja más de su velocidad (en línea
	//recta desde dónde empezó el turno) desde moveOrigin, se le devuelve a la última posición vista dentro
	//de alcance. ponytail: distancia en línea recta desde el origen, no ruta acumulada ni solo horizontal —
	//suficiente para cortar el "vuelo libre" de Minecraft, no un tracker de casillas real.
	void enforceMovementBudget(ServerPlayer player) {
		if (enforceBudget(player, speedBlocksFor(SheetLoader.getServerSheet(player.getStringUUID())))) {
			CombatFx.actionBar(player, Component.translatable("chat.dndsheets.turn.no_movement_left").withStyle(ChatFormatting.RED));
		}
	}

	//Mismo mecanismo que arriba, para un mob de compatibilidad (ver TurnManager.isMonster): sin HUD que
	//avisar, así que el llamador (TurnManager.onMobTick) es quien decide qué hacer con el resultado —
	//gastar su turno, en su caso, ya que un mob no tiene "seguir intentando" tras quedarse sin movimiento.
	//@return true si se agotó su presupuesto de movimiento (y se le devolvió a su última posición válida).
	boolean enforceMobMovementBudget(Entity entity, double speedBlocks) {
		return enforceBudget(entity, speedBlocks);
	}

	private boolean enforceBudget(Entity entity, double speedBlocks) {
		int id = entity.getId();
		Pinned origin = moveOrigin.get(id);
		if (origin == null) return false;
		if (entity.level().dimension() != origin.dimension()) {
			//Cruzó a otro nivel (portal) con el turno activo: las coordenadas grabadas ya no significan
			//nada acá — se suelta el presupuesto en vez de comparar/teletransportar entre dimensiones.
			moveOrigin.remove(id);
			lastGoodPos.remove(id);
			return false;
		}
		Vec3 pos = entity.position();
		if (pos.distanceTo(origin.pos()) <= speedBlocks) {
			lastGoodPos.put(id, new Pinned(origin.dimension(), pos));
			return false;
		}
		Vec3 fallback = lastGoodPos.getOrDefault(id, origin).pos();
		entity.teleportTo(fallback.x, fallback.y, fallback.z);
		entity.setDeltaMovement(Vec3.ZERO);
		return true;
	}
}
