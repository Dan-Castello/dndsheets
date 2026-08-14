package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.DungeonPieceAddScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente (el DM): abre el formulario de "Añadir pieza" prellenado con el id que ya tenía el
//bloque de estructura al que le acaba de hacer clic derecho con la Vara de DM (ver DungeonToolManager) —
//sin esto, el DM tenía que retipear a mano el mismo id que ya escribió una vez al guardar la estructura.
public class DungeonPieceAddOpenMessage {
	String structureId, suggestedId;

	public DungeonPieceAddOpenMessage(String structureId, String suggestedId) {
		this.structureId = structureId;
		this.suggestedId = suggestedId;
	}

	public DungeonPieceAddOpenMessage(FriendlyByteBuf buffer) {
		this.structureId = buffer.readUtf();
		this.suggestedId = buffer.readUtf();
	}

	public static void buffer(DungeonPieceAddOpenMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.structureId);
		buffer.writeUtf(message.suggestedId);
	}

	public static void handler(DungeonPieceAddOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> DungeonPieceAddScreen.open(message.structureId, message.suggestedId));
	}
}
