package net.hawthorn.dndsheets.dungeon;

import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Puebla las salas de una mazmorra recién generada sin que un DM las rellene a mano —ver
 * {@link DungeonManager#generate}, que llama aquí justo después de plantar la estructura, con el
 * bounding box real de cada pieza (no se puede recalcular después: la colocación jigsaw es
 * aleatoria).</p>
 *
 * <p><b>Heurística deliberadamente mínima, no un presupuesto de encuentro estilo DMG:</b>
 * {@link MonsterRegistry.MonsterStatBlock} no trae Valor de Desafío ni nivel — solo PG, CA y ataques—,
 * así que "filtrar por nivel de personaje" se aproxima con una banda de PG máximos
 * ({@code HP_FLOOR + playerLevel * HP_PER_LEVEL}). No calcula presupuesto de XP por sala ni balancea
 * dificultad real; si el playtesting muestra que la banda queda mal calibrada, ese ajuste es una
 * iteración posterior, no parte de esta pasada.</p>
 *
 * <p>ponytail: banda de PG plana, no una tabla de VD real — subir a algo más fiel al DMG cuando haya
 * datos de VD reales en el bloque de estadísticas o el playtesting lo pida.</p>
 */
public final class EncounterPopulator {
	private EncounterPopulator() {}

	private static final double ROOM_POPULATE_CHANCE = 0.6;
	private static final int MIN_MONSTERS_PER_ROOM = 1;
	private static final int MAX_MONSTERS_PER_ROOM = 3;
	private static final int HP_FLOOR = 10;
	private static final int HP_PER_LEVEL = 15;
	//Descarta pasillos angostos como "sala": un corredor de 3 bloques de ancho no es donde un DM
	//pondría un encuentro, y llenarlo de monstruos los deja pegados contra los jigsaw de conexión.
	private static final int MIN_ROOM_VOLUME = 4 * 4 * 3;

	public static void populate(ServerLevel level, List<BoundingBox> roomBounds, int playerLevel) {
		List<String> candidates = candidateMonsters(playerLevel);
		if (candidates.isEmpty()) return; //Sin bestiario cargado que encaje, no hay nada que poblar.

		RandomSource random = level.getRandom();
		for (BoundingBox room : roomBounds) {
			if (volumeOf(room) < MIN_ROOM_VOLUME) continue;
			if (random.nextDouble() > ROOM_POPULATE_CHANCE) continue;

			BlockPos center = room.getCenter();
			int count = MIN_MONSTERS_PER_ROOM + random.nextInt(MAX_MONSTERS_PER_ROOM - MIN_MONSTERS_PER_ROOM + 1);
			for (int i = 0; i < count; i++) {
				String monsterId = candidates.get(random.nextInt(candidates.size()));
				//Un poco de dispersión (±1 bloque) para que no queden los tres apilados en la misma casilla.
				double x = center.getX() + random.nextInt(3) - 1;
				double z = center.getZ() + random.nextInt(3) - 1;
				MonsterRegistry.spawnAt(level, x, center.getY(), z, monsterId);
			}
		}
	}

	private static int volumeOf(BoundingBox box) {
		return box.getXSpan() * box.getYSpan() * box.getZSpan();
	}

	private static List<String> candidateMonsters(int playerLevel) {
		int maxHp = HP_FLOOR + playerLevel * HP_PER_LEVEL;
		List<String> ids = new ArrayList<>();
		for (String id : MonsterRegistry.ids()) {
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(id);
			if (block != null && block.maxHp() <= maxHp) ids.add(id);
		}
		return ids;
	}
}
