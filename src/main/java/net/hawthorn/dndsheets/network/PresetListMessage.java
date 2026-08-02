package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.PresetScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//Servidor -> cliente: la lista de presets cargados (ids + nombres), para abrir el selector con datos reales
//aunque el cliente y el servidor sean procesos distintos (el registro en memoria solo vive en el servidor).
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class PresetListMessage {
	List<String> ids;
	List<String> names;

	public PresetListMessage(List<String> ids, List<String> names) {
		this.ids = ids;
		this.names = names;
	}

	public PresetListMessage(FriendlyByteBuf buffer) {
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
		this.names = buffer.readList(FriendlyByteBuf::readUtf);
	}

	public static void buffer(PresetListMessage message, FriendlyByteBuf buffer) {
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.names, FriendlyByteBuf::writeUtf);
	}

	public static void handler(PresetListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PresetScreen.open(message.ids, message.names)));
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(PresetListMessage.class, PresetListMessage::buffer, PresetListMessage::new, PresetListMessage::handler);
	}
}
