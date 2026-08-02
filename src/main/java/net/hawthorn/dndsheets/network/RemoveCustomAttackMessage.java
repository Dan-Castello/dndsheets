package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: quitó un ataque personalizado desde ManageCustomAttacksScreen.
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
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

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(RemoveCustomAttackMessage.class, RemoveCustomAttackMessage::buffer, RemoveCustomAttackMessage::new, RemoveCustomAttackMessage::handler);
	}
}
