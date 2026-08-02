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

//Servidor -> cliente: cierra la ventana de salvación de muerte porque el jugador se estabilizó o murió de verdad.
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class DeathSaveCloseMessage {
	public DeathSaveCloseMessage() {}

	public DeathSaveCloseMessage(FriendlyByteBuf buffer) {}

	public static void buffer(DeathSaveCloseMessage message, FriendlyByteBuf buffer) {}

	public static void handler(DeathSaveCloseMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> DeathSaveScreen::close));
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(DeathSaveCloseMessage.class, DeathSaveCloseMessage::buffer, DeathSaveCloseMessage::new, DeathSaveCloseMessage::handler);
	}
}
