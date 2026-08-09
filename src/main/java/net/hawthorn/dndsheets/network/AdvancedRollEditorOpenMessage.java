
package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.procedures.AdvancedRollEditorOpenProcedure;
import net.hawthorn.dndsheets.procedures.RollEditorOpenProcedure;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class AdvancedRollEditorOpenMessage {

	public AdvancedRollEditorOpenMessage() {
	}

	public AdvancedRollEditorOpenMessage(FriendlyByteBuf buffer) {
	}

	public static void buffer(AdvancedRollEditorOpenMessage message, FriendlyByteBuf buffer) {
	}

	public static void handler(AdvancedRollEditorOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> pressAction(context.getSender()));
	}

	public static void pressAction(Player entity) {
		Level world = entity.level();
		double x = entity.getX();
		double y = entity.getY();
		double z = entity.getZ();
		// security measure to prevent arbitrary chunk generation
		if (!world.hasChunkAt(entity.blockPosition()))
			return;

		AdvancedRollEditorOpenProcedure.execute(world, x, y, z, entity);
	}
}
