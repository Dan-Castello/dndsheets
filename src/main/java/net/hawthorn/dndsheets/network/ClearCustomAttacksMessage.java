package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: "Borrar todos" en ManageCustomAttacksScreen.
public class ClearCustomAttacksMessage {
	int entityId;

	public ClearCustomAttacksMessage(int entityId) {
		this.entityId = entityId;
	}

	public ClearCustomAttacksMessage(FriendlyByteBuf buffer) {
		this.entityId = buffer.readVarInt();
	}

	public static void buffer(ClearCustomAttacksMessage message, FriendlyByteBuf buffer) {
		buffer.writeVarInt(message.entityId);
	}

	public static void handler(ClearCustomAttacksMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> {

			Entity target = dm.level().getEntity(message.entityId);
			if (target == null) return;
			MonsterRegistry.clearCustomAttacks(target);
		});
	}
}
