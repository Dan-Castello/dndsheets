package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.TraitGrantScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//Servidor -> cliente: la lista de rasgos cargados (ids + nombres) para el objetivo que se eligió en
//PlayerPickerScreen, abre TraitGrantScreen con datos reales.
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class TraitListMessage {
	String targetUuid;
	List<String> ids;
	List<String> names;

	public TraitListMessage(String targetUuid, List<String> ids, List<String> names) {
		this.targetUuid = targetUuid;
		this.ids = ids;
		this.names = names;
	}

	public TraitListMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
		this.names = buffer.readList(FriendlyByteBuf::readUtf);
	}

	public static void buffer(TraitListMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.names, FriendlyByteBuf::writeUtf);
	}

	public static void handler(TraitListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> TraitGrantScreen.open(message.targetUuid, message.ids, message.names)));
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(TraitListMessage.class, TraitListMessage::buffer, TraitListMessage::new, TraitListMessage::handler);
	}
}
