package net.hawthorn.dndsheets.dungeon;

import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Populates the rooms of a freshly generated dungeon so a DM doesn't have to fill them by hand —
 * see {@link DungeonManager#generate}, which calls here right after planting the structure, with the
 * real bounding box of each piece (it can't be recomputed afterward: jigsaw placement is random).</p>
 *
 * <p><b>Deliberately minimal heuristic, not a DMG-style encounter budget:</b>
 * {@link MonsterRegistry.MonsterStatBlock} carries no Challenge Rating or level — only HP, AC and
 * attacks — so "filter by character level" is approximated with a max-HP band
 * ({@code HP_FLOOR + playerLevel * HP_PER_LEVEL}). It doesn't compute an XP budget per room or balance
 * real difficulty; if playtesting shows the band is miscalibrated, that tuning is a later iteration,
 * not part of this pass.</p>
 *
 * <p>ponytail: flat HP band, not a real CR table — upgrade to something closer to the DMG once real
 * CR data exists on the stat block or playtesting calls for it.</p>
 */
public final class EncounterPopulator {
	private EncounterPopulator() {}

	private static final double ROOM_POPULATE_CHANCE = 0.6;
	private static final int MIN_MONSTERS_PER_ROOM = 1;
	private static final int MAX_MONSTERS_PER_ROOM = 3;
	private static final int HP_FLOOR = 10;
	private static final int HP_PER_LEVEL = 15;
	//Excludes narrow corridors from counting as a "room": a 3-block-wide corridor isn't where a DM
	//would put an encounter, and filling it with monsters leaves them stuck against the connector jigsaws.
	private static final int MIN_ROOM_VOLUME = 4 * 4 * 3;

	public static void populate(ServerLevel level, List<BoundingBox> roomBounds, int playerLevel) {
		List<String> candidates = candidateMonsters(playerLevel);
		if (candidates.isEmpty()) return; //No loaded bestiary entry fits, nothing to populate.

		RandomSource random = level.getRandom();
		for (BoundingBox room : roomBounds) {
			if (volumeOf(room) < MIN_ROOM_VOLUME) continue;
			if (random.nextDouble() > ROOM_POPULATE_CHANCE) continue;

			BlockPos center = room.getCenter();
			int count = MIN_MONSTERS_PER_ROOM + random.nextInt(MAX_MONSTERS_PER_ROOM - MIN_MONSTERS_PER_ROOM + 1);
			for (int i = 0; i < count; i++) {
				String monsterId = candidates.get(random.nextInt(candidates.size()));
				//A bit of scatter (±1 block) so they don't all stack up on the same tile.
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
