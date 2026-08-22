package net.hawthorn.dndsheets;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * <p>Acciones legendarias: un jefe actúa <b>al terminar el turno de otro</b>, no solo en el suyo. Es la
 * regla que hace que un dragón contra cuatro jugadores sea una pelea y no un intercambio de turnos donde
 * el grupo pega cuatro veces por cada una suya.</p>
 *
 * <p>La otra mitad de la Resistencia Legendaria, y la que de verdad cambia cómo se siente el combate. Sin
 * ella, el bestiario tenía 43 dragones, una tarrasca y un lich que peleaban como un goblin grande.</p>
 *
 * <p><b>Modelo deliberadamente reducido a "un ataque".</b> En el SRD cada jefe tiene su propia lista de
 * acciones legendarias con costes distintos (Atacar por 1, Aletazo por 2, Detectar por 1...). Aquí una
 * acción legendaria es <em>un ataque de los suyos</em>, con coste 1, hasta agotar su presupuesto del asalto.
 * Es la que casi todos comparten y la que decide el combate; inventar un esquema para las demás sería
 * escribir un campo por jefe para capacidades que además no tienen a quién apuntar en este mod (moverse sin
 * provocar, detectar, cambiar el terreno).</p>
 *
 * <p>El presupuesto va en la etiqueta NBT de la entidad, igual que los PG y las resistencias legendarias:
 * es del individuo, no de la especie, y Minecraft ya lo guarda y lo carga solo.</p>
 */
public class LegendaryActionManager {

	private static final String LEGENDARY_ACTIONS_LEFT = "legendaryActionsLeft";
	/** Hasta dónde busca a quién castigar. El mismo que usa el resto de acciones de monstruo. */
	private static final double TARGET_RANGE = 30.0;

	/**
	 * <p>Se llama al TERMINAR el turno de alguien, que es cuando 5e deja actuar a un jefe. Cada criatura
	 * legendaria del orden de turnos —menos la que acaba de jugar— gasta una acción si le queda.</p>
	 *
	 * <p>No se le da la acción al que acaba de terminar su propio turno: en 5e son acciones para actuar
	 * <em>fuera</em> del suyo, y dárselas ahí le regalaría un ataque extra pegado al que ya hizo.</p>
	 */
	static void onTurnEnded(ServerLevel level, TurnManager.TurnEntry finishing, List<TurnManager.TurnEntry> order) {
		for (TurnManager.TurnEntry entry : order) {
			if (finishing != null && entry.entityId() == finishing.entityId()) continue;
			if (!entry.isMonster()) continue;

			Entity boss = level.getEntity(entry.entityId());
			if (boss == null || !boss.isAlive()) continue;
			//Un jefe paralizado, aturdido o inconsciente no toma acciones legendarias: en 5e lo dice la propia
			//regla ("no puede usarlas mientras esté incapacitado"), y sin esto un dragón dormido seguía
			//repartiendo tres ataques por asalto. Se comprueba ANTES de gastar, para no cobrarle un uso por
			//una acción que no llega a ocurrir.
			if (TurnManager.isIncapacitated(boss)) continue;
			if (!spendAction(boss)) continue;

			Player target = level.getNearestPlayer(boss, TARGET_RANGE);
			if (target == null) {
				//Nadie a tiro: se devuelve el uso en vez de tirarlo. Gastarlo contra nadie castigaría al jefe
				//por dónde está el grupo, que es justo lo contrario de lo que la regla hace.
				refundAction(boss);
				continue;
			}
			MonsterActionManager.resolveLegendaryAttack(boss, target);
		}
	}

	/** Al empezar su propio turno recupera todas: es como se recargan en 5e. */
	static void onOwnTurnStart(Entity boss) {
		int budget = budgetOf(boss);
		if (budget <= 0) return;
		write(boss, budget);
	}

	private static int budgetOf(Entity entity) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(entity);
		if (block == null) return 0;
		//Con reloj propio, ninguna. Las acciones legendarias existen en 5e para que un jefe pueda hacer
		//algo mientras espera su turno; quien no espera turno no las necesita, y dárselas igual sería
		//darle dos economías de acción a la vez.
		if (block.ownClock()) return 0;
		return block.legendaryActions();
	}

	/**
	 * <p>Gasta una acción. Devuelve false si la criatura no es legendaria o si ya agotó su presupuesto
	 * este asalto.</p>
	 *
	 * <p>Sin la etiqueta puesta todavía cuenta como "las tiene todas", igual que la Resistencia Legendaria:
	 * un jefe invocado antes de que existiera la regla no debería quedarse sin ellas para siempre.</p>
	 */
	private static boolean spendAction(Entity boss) {
		int budget = budgetOf(boss);
		if (budget <= 0) return false;
		CompoundTag tag = boss.getPersistentData().getCompound("dndsheets");
		int left = tag.contains(LEGENDARY_ACTIONS_LEFT) ? tag.getInt(LEGENDARY_ACTIONS_LEFT) : budget;
		if (left <= 0) return false;
		write(boss, left - 1);
		return true;
	}

	private static void refundAction(Entity boss) {
		CompoundTag tag = boss.getPersistentData().getCompound("dndsheets");
		write(boss, Math.min(budgetOf(boss), tag.getInt(LEGENDARY_ACTIONS_LEFT) + 1));
	}

	private static void write(Entity boss, int left) {
		CompoundTag data = boss.getPersistentData();
		CompoundTag tag = data.getCompound("dndsheets");
		tag.putInt(LEGENDARY_ACTIONS_LEFT, Math.max(0, left));
		data.put("dndsheets", tag);
	}
}
