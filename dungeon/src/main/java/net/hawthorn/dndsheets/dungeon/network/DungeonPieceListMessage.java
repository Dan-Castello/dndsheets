package net.hawthorn.dndsheets.dungeon.network;
import net.hawthorn.dndsheets.network.NetworkUtil;

import net.hawthorn.dndsheets.dungeon.DungeonManager;
import net.hawthorn.dndsheets.dungeon.DungeonPieceRegistry;
import net.hawthorn.dndsheets.dungeon.client.gui.DungeonPieceListScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

//Server -> client: the list of registered dungeon pieces (see DungeonPieceListRequestMessage), or the
//echo after capturing/editing one to refresh the screen — same parallel-lists approach as
//PresetListMessage instead of its own codec, there aren't enough fields to justify one.
public class DungeonPieceListMessage {
	List<String> ids;
	List<String> structureIds;
	List<String> pools;
	List<Integer> weights;
	List<String> tags;
	//DungeonManager.hasStartJigsaw per piece — so DungeonPieceListScreen can mark which ones have the
	//start jigsaw, visible BEFORE attempting to generate (see the real problem with mixing entry pieces
	//with regular pieces in the same pool, DungeonManager.generate()).
	List<Boolean> hasStart;

	public DungeonPieceListMessage(List<String> ids, List<String> structureIds, List<String> pools, List<Integer> weights, List<String> tags, List<Boolean> hasStart) {
		this.ids = ids;
		this.structureIds = structureIds;
		this.pools = pools;
		this.weights = weights;
		this.tags = tags;
		this.hasStart = hasStart;
	}

	public static DungeonPieceListMessage of(ServerLevel level, Collection<DungeonPieceRegistry.DungeonPiece> pieces) {
		List<String> ids = new ArrayList<>();
		List<String> structureIds = new ArrayList<>();
		List<String> pools = new ArrayList<>();
		List<Integer> weights = new ArrayList<>();
		List<String> tags = new ArrayList<>();
		List<Boolean> hasStart = new ArrayList<>();
		for (DungeonPieceRegistry.DungeonPiece piece : pieces) {
			ids.add(piece.id());
			structureIds.add(piece.structureId());
			pools.add(piece.pool());
			weights.add(piece.weight());
			tags.add(piece.tags());
			hasStart.add(DungeonManager.hasStartJigsaw(level, piece));
		}
		return new DungeonPieceListMessage(ids, structureIds, pools, weights, tags, hasStart);
	}

	public DungeonPieceListMessage(FriendlyByteBuf buffer) {
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
		this.structureIds = buffer.readList(FriendlyByteBuf::readUtf);
		this.pools = buffer.readList(FriendlyByteBuf::readUtf);
		this.weights = buffer.readList(FriendlyByteBuf::readVarInt);
		this.tags = buffer.readList(FriendlyByteBuf::readUtf);
		this.hasStart = buffer.readList(FriendlyByteBuf::readBoolean);
	}

	public static void buffer(DungeonPieceListMessage message, FriendlyByteBuf buffer) {
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.structureIds, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.pools, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.weights, FriendlyByteBuf::writeVarInt);
		buffer.writeCollection(message.tags, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.hasStart, FriendlyByteBuf::writeBoolean);
	}

	public static void handler(DungeonPieceListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> {
			List<DungeonPieceRegistry.DungeonPiece> pieces = new ArrayList<>();
			for (int i = 0; i < message.ids.size(); i++) {
				pieces.add(new DungeonPieceRegistry.DungeonPiece(message.ids.get(i), message.structureIds.get(i), message.pools.get(i), message.weights.get(i), message.tags.get(i)));
			}
			DungeonPieceListScreen.open(pieces, message.hasStart);
		});
	}
}
