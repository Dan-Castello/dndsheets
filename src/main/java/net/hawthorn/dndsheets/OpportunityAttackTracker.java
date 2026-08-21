package net.hawthorn.dndsheets;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

//Ataques de oportunidad del modo turnos — extraído de TurnManager (ver hallazgo F3): qué
//monstruos tenían a quien se mueve al alcance cuerpo a cuerpo, para dispararles la reacción en cuanto se
//alejan sin desengancharse. Estado propio; recibe el orden de turnos como parámetro en vez de leer el
//campo de TurnManager directamente.
class OpportunityAttackTracker {
	//Alcance cuerpo a cuerpo aproximado (mismo rango que ya usa Minecraft para golpear).
	//Package-private a propósito: MonsterActionManager reusa este mismo valor para decidir cuándo un
	//monstruo propio ya está lo bastante cerca para atacar sin acercarse más (ver autoAct/moveTowardIfNeeded).
	static final double MELEE_REACH = 3.0;
	private final Set<Integer> withinReach = new HashSet<>();

	//Se guarda la última posición ya comprobada para saltar el recorrido de todos los combatientes si no
	//cambió desde el tick anterior — checkOpportunityAttacks corría en cada tick del jugador con el turno,
	//incluso parado quieto.
	private final Map<Integer, Vec3> lastCheckedPos = new HashMap<>();

	void clear() {
		withinReach.clear();
	}

	void rekey(int oldId, int newId) {
		if (withinReach.remove(oldId)) withinReach.add(newId);
	}

	//Al empezar el turno de un jugador, se anota qué monstruos lo tienen ya al alcance (adyacentes desde el
	//principio, sin "salir" de nada) para no dispararles una reacción falsa en el primer tick de su turno.
	void seedReachState(ServerLevel level, ServerPlayer mover, List<TurnManager.TurnEntry> order) {
		withinReach.clear();
		//Sin esto, si el jugador termina un turno anterior y empieza este SIN moverse de esa posición, el
		//primer tick del turno nuevo vería la misma posición "ya comprobada" del turno anterior y se
		//saltaría el chequeo de oportunidad que en realidad hace falta reevaluar desde cero.
		lastCheckedPos.remove(mover.getId());
		for (TurnManager.TurnEntry entry : order) {
			if (entry.entityId() == mover.getId()) continue;
			Entity entity = level.getEntity(entry.entityId());
			if (entity != null && MonsterRegistry.statBlockOf(entity) != null && entity.position().distanceTo(mover.position()) <= MELEE_REACH) {
				withinReach.add(entry.entityId());
			}
		}
	}

	//Llamado cada tick mientras el jugador que se mueve libremente (tiene el turno) sigue en pie: cualquier
	//monstruo del orden de turnos que estuviera a alcance cuerpo a cuerpo el tick anterior y ya no lo esté
	//ahora (se alejó sin desengancharse) gasta su reacción en un ataque de oportunidad con su primer ataque
	//real disponible. Simplificación deliberada: solo monstruos, no PvP entre jugadores (los demás
	//jugadores están anclados y no pueden moverse de todos modos mientras no sea su turno).
	void checkOpportunityAttacks(ServerLevel level, ServerPlayer mover, List<TurnManager.TurnEntry> order) {
		//Desengancharse: alejarse deja de provocar reacciones este turno. Se sale ANTES de recorrer nada,
		//pero sin tocar withinReach — cuando el turno acabe y la acción caduque, el registro de quién lo
		//tenía a alcance tiene que seguir siendo el bueno.
		if (TurnActionManager.isDisengaged(mover)) return;
		Vec3 pos = mover.position();
		if (pos.equals(lastCheckedPos.get(mover.getId()))) return; //Sin cambio de posición, nada que reevaluar.
		lastCheckedPos.put(mover.getId(), pos);

		for (TurnManager.TurnEntry entry : order) {
			if (entry.entityId() == mover.getId()) continue;
			Entity entity = level.getEntity(entry.entityId());
			if (entity == null || !entity.isAlive() || MonsterRegistry.statBlockOf(entity) == null) continue;

			boolean nowInReach = entity.position().distanceTo(mover.position()) <= MELEE_REACH;
			if (nowInReach) {
				withinReach.add(entry.entityId());
			} else if (withinReach.remove(entry.entityId()) && TurnManager.tryReact(entity)) {
				MonsterActionManager.resolveOpportunityAttack(entity, mover);
			}
		}
	}
}
