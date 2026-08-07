package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.TraitGrantScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//Servidor -> cliente: la lista de rasgos cargados (ids + nombres) para el objetivo que se eligió en
//PlayerPickerScreen, abre TraitGrantScreen con datos reales.
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
}
