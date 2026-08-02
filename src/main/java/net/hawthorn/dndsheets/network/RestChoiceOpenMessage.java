package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.RestChoiceScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente: usó el Kit de Descanso, que elija corto o largo.
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class RestChoiceOpenMessage {
	public RestChoiceOpenMessage() {}

	public RestChoiceOpenMessage(FriendlyByteBuf buffer) {}

	public static void buffer(RestChoiceOpenMessage message, FriendlyByteBuf buffer) {}

	public static void handler(RestChoiceOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> RestChoiceScreen::open));
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(RestChoiceOpenMessage.class, RestChoiceOpenMessage::buffer, RestChoiceOpenMessage::new, RestChoiceOpenMessage::handler);
	}
}
