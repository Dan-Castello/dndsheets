package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DeathSaveManager;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente -> servidor: el jugador caído pulsó "Tirar salvación de muerte".
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class DeathSaveRollMessage {
	public DeathSaveRollMessage() {}

	public DeathSaveRollMessage(FriendlyByteBuf buffer) {}

	public static void buffer(DeathSaveRollMessage message, FriendlyByteBuf buffer) {}

	public static void handler(DeathSaveRollMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer player = context.getSender();
			if (player != null) {
				DeathSaveManager.handleRollRequest(player);
			}
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(DeathSaveRollMessage.class, DeathSaveRollMessage::buffer, DeathSaveRollMessage::new, DeathSaveRollMessage::handler);
	}
}
