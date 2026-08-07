package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: quitó un ataque personalizado desde ManageCustomAttacksScreen.
public class RemoveCustomAttackMessage {
	int entityId;
	String name;

	public RemoveCustomAttackMessage(int entityId, String name) {
		this.entityId = entityId;
		this.name = name;
	}

	public RemoveCustomAttackMessage(FriendlyByteBuf buffer) {
		this.entityId = buffer.readVarInt();
		this.name = buffer.readUtf();
	}

	public static void buffer(RemoveCustomAttackMessage message, FriendlyByteBuf buffer) {
		buffer.writeVarInt(message.entityId);
		buffer.writeUtf(message.name);
	}

	public static void handler(RemoveCustomAttackMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;

			Entity target = dm.level().getEntity(message.entityId);
			if (target == null) return;
			MonsterRegistry.removeCustomAttack(target, message.name);
		});
		context.setPacketHandled(true);
	}
}
