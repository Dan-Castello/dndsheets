
package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.procedures.RollEditorOpenProcedure;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class RollEditorOpenMessage {

	public RollEditorOpenMessage() {
	}

	public RollEditorOpenMessage(FriendlyByteBuf buffer) {
	}

	public static void buffer(RollEditorOpenMessage message, FriendlyByteBuf buffer) {
	}

	public static void handler(RollEditorOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			pressAction(context.getSender());
		});
		context.setPacketHandled(true);
	}

	public static void pressAction(Player entity) {
		Level world = entity.level();
		double x = entity.getX();
		double y = entity.getY();
		double z = entity.getZ();
		// security measure to prevent arbitrary chunk generation
		if (!world.hasChunkAt(entity.blockPosition()))
			return;

		RollEditorOpenProcedure.execute(world, x, y, z, entity);
	}
}
