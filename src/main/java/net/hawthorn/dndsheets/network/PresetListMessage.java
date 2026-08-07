package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.PresetScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//Servidor -> cliente: la lista de presets cargados (ids + nombres), para abrir el selector con datos reales
//aunque el cliente y el servidor sean procesos distintos (el registro en memoria solo vive en el servidor).
public class PresetListMessage {
	String targetUuid;
	List<String> ids;
	List<String> names;

	public PresetListMessage(String targetUuid, List<String> ids, List<String> names) {
		this.targetUuid = targetUuid;
		this.ids = ids;
		this.names = names;
	}

	public PresetListMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
		this.names = buffer.readList(FriendlyByteBuf::readUtf);
	}

	public static void buffer(PresetListMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.names, FriendlyByteBuf::writeUtf);
	}

	public static void handler(PresetListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PresetScreen.open(message.targetUuid, message.ids, message.names)));
		context.setPacketHandled(true);
	}
}
