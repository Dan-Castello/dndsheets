package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.DeathSaveScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente: abre la ventana de salvación de muerte porque el jugador acaba de caer a 0 PG.
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class DeathSaveOpenMessage {
	public DeathSaveOpenMessage() {}

	public DeathSaveOpenMessage(FriendlyByteBuf buffer) {}

	public static void buffer(DeathSaveOpenMessage message, FriendlyByteBuf buffer) {}

	public static void handler(DeathSaveOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> DeathSaveScreen::open));
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(DeathSaveOpenMessage.class, DeathSaveOpenMessage::buffer, DeathSaveOpenMessage::new, DeathSaveOpenMessage::handler);
	}
}
