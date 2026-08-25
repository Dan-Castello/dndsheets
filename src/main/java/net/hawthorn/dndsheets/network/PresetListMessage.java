package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.PresetScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//Servidor -> cliente: la lista de presets cargados (ids + nombres), para abrir el selector con datos reales
//aunque el cliente y el servidor sean procesos distintos (el registro en memoria solo vive en el servidor).
public class PresetListMessage {
	String targetUuid;
	//Ver PresetListRequestMessage.multiclass: viaja de vuelta para que el cliente sepa en qué modo abrir
	//PresetScreen sin tener que acordarse por su cuenta de qué pidió.
	boolean multiclass;
	List<String> ids;
	List<String> names;

	public PresetListMessage(String targetUuid, boolean multiclass, List<String> ids, List<String> names) {
		this.targetUuid = targetUuid;
		this.multiclass = multiclass;
		this.ids = ids;
		this.names = names;
	}

	public PresetListMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.multiclass = buffer.readBoolean();
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
		this.names = buffer.readList(FriendlyByteBuf::readUtf);
	}

	public static void buffer(PresetListMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeBoolean(message.multiclass);
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.names, FriendlyByteBuf::writeUtf);
	}

	public static void handler(PresetListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> PresetScreen.open(message.targetUuid, message.multiclass, message.ids, message.names));
	}
}
