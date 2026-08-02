package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.RestVoteScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente: cierra la ventana de votación de descanso porque la propuesta ya se resolvió,
//expiró, o fue rechazada/cancelada por otra vía — sin esto, quien no había votado todavía se quedaba con
//una pantalla de "Aceptar/Rechazar" para una votación que ya no existía (ver RestManager.clear()).
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class RestVoteCloseMessage {
	public RestVoteCloseMessage() {}

	public RestVoteCloseMessage(FriendlyByteBuf buffer) {}

	public static void buffer(RestVoteCloseMessage message, FriendlyByteBuf buffer) {}

	public static void handler(RestVoteCloseMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> RestVoteScreen::close));
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(RestVoteCloseMessage.class, RestVoteCloseMessage::buffer, RestVoteCloseMessage::new, RestVoteCloseMessage::handler);
	}
}
