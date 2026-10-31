package net.hawthorn.dndsheets.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Pastes into the world (a drawn room, an imported dungeon) with their undo recorded first. A small one
 * goes in at once; a big one a few thousand blocks per tick, with its progress on the DM's action bar, so a
 * whole dungeon doesn't freeze the server. One paste at a time per DM.</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsDungeonMod.MODID)
public final class PasteQueue {
	private static final int IMMEDIATE_LIMIT = 8192;
	private static final int PER_TICK = 4096;

	private static final class Job {
		final ServerLevel level;
		final BlockPos origin;
		final List<GridToStructure.PlacedBlock> blocks;
		final DungeonPiecePlacer.Materials materials;
		final Component doneMessage;
		int done;

		Job(ServerLevel level, BlockPos origin, List<GridToStructure.PlacedBlock> blocks, DungeonPiecePlacer.Materials materials, Component doneMessage) {
			this.level = level;
			this.origin = origin;
			this.blocks = blocks;
			this.materials = materials;
			this.doneMessage = doneMessage;
		}
	}

	private static final Map<UUID, Job> JOBS = new HashMap<>();

	private PasteQueue() {}

	public static boolean busy(ServerPlayer dm) {
		return JOBS.containsKey(dm.getUUID());
	}

	/** Stops the DM's paste where it got to (before undoing it). */
	public static void cancel(ServerPlayer dm) {
		JOBS.remove(dm.getUUID());
	}

	/** Records the undo, then places the blocks now or over the next ticks; {@code doneMessage} goes to chat at the end. */
	public static void paste(ServerPlayer dm, ServerLevel level, BlockPos origin, List<GridToStructure.PlacedBlock> blocks,
			DungeonPiecePlacer.Materials materials, Component doneMessage) {
		PasteHistory.record(dm, level, origin, blocks);
		if (blocks.size() <= IMMEDIATE_LIMIT) {
			DungeonPiecePlacer.place(level, origin, blocks, materials, "", false);
			dm.sendSystemMessage(doneMessage);
			return;
		}
		JOBS.put(dm.getUUID(), new Job(level, origin, blocks, materials, doneMessage));
	}

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) return;
		var iterator = JOBS.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			Job job = entry.getValue();
			int end = Math.min(job.blocks.size(), job.done + PER_TICK);
			DungeonPiecePlacer.place(job.level, job.origin, job.blocks.subList(job.done, end), job.materials, "", false);
			int before = job.done * 4 / job.blocks.size();
			job.done = end;
			ServerPlayer dm = job.level.getServer().getPlayerList().getPlayer(entry.getKey());
			if (end >= job.blocks.size()) {
				iterator.remove();
				if (dm != null) dm.sendSystemMessage(job.doneMessage);
			} else if (dm != null && end * 4 / job.blocks.size() != before) {
				dm.displayClientMessage(Component.translatable("chat.dndsheets.dungeon.paste_progress", end * 100 / job.blocks.size()), true);
			}
		}
	}

	@SubscribeEvent
	public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		JOBS.remove(event.getEntity().getUUID());
	}
}
