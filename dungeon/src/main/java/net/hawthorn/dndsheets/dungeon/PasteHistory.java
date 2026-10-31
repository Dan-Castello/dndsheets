package net.hawthorn.dndsheets.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p>What a pasted room overwrote, so the DM can take the paste back ({@code /dnddungeon undopaste}, the chat
 * link, or Undo in the editor). Per DM, the last few pastes, newest first; block entities (chests, signs)
 * are kept with their contents.</p>
 *
 * <p>ponytail: in memory only — a server restart forgets it, which is fine for "oops, wrong spot".</p>
 */
public final class PasteHistory {
	private static final int MAX_PER_DM = 5;

	private record Saved(BlockPos pos, BlockState state, CompoundTag blockEntity) {}

	private record Paste(ResourceKey<Level> dimension, List<Saved> blocks) {}

	private static final Map<UUID, Deque<Paste>> HISTORY = new HashMap<>();

	private PasteHistory() {}

	/** Call BEFORE placing: remembers what is at each position the paste will touch. */
	public static void record(ServerPlayer dm, ServerLevel level, BlockPos origin, List<GridToStructure.PlacedBlock> blocks) {
		List<Saved> saved = new ArrayList<>();
		for (GridToStructure.PlacedBlock block : blocks) {
			BlockPos at = origin.offset(block.x(), block.y(), block.z());
			BlockEntity entity = level.getBlockEntity(at);
			saved.add(new Saved(at, level.getBlockState(at), entity != null ? entity.saveWithFullMetadata() : null));
		}
		Deque<Paste> pastes = HISTORY.computeIfAbsent(dm.getUUID(), id -> new ArrayDeque<>());
		pastes.push(new Paste(level.dimension(), saved));
		while (pastes.size() > MAX_PER_DM) pastes.removeLast();
	}

	/** Puts back what the DM's last paste replaced. Returns how many blocks were restored, -1 if there was nothing to undo. */
	public static int undo(ServerPlayer dm) {
		Deque<Paste> pastes = HISTORY.get(dm.getUUID());
		Paste paste = pastes != null ? pastes.poll() : null;
		if (paste == null) return -1;
		ServerLevel level = dm.server.getLevel(paste.dimension());
		if (level == null) return -1;
		//Backwards: where the paste wrote a position twice, the first save holds the original.
		List<Saved> blocks = paste.blocks();
		for (int i = blocks.size() - 1; i >= 0; i--) {
			Saved saved = blocks.get(i);
			level.setBlock(saved.pos(), saved.state(), 2);
			if (saved.blockEntity() != null) {
				BlockEntity entity = level.getBlockEntity(saved.pos());
				if (entity != null) entity.load(saved.blockEntity());
			}
		}
		return blocks.size();
	}
}
