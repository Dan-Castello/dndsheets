package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.MonsterActionScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//Servidor -> cliente (el DM): abre el menú de ataques/hechizos de un monstruo que acaba de tocar con la vara de DM.
public class MonsterActionOpenMessage {
	int entityId;
	List<String> actionNames;
	List<String> customAttackNames; //Subconjunto de actionNames añadido en vivo (ver MonsterRegistry.addCustomAttack): el menú los ofrece para editar/quitar aparte de los predefinidos.

	public MonsterActionOpenMessage(int entityId, List<String> actionNames, List<String> customAttackNames) {
		this.entityId = entityId;
		this.actionNames = actionNames;
		this.customAttackNames = customAttackNames;
	}

	public MonsterActionOpenMessage(FriendlyByteBuf buffer) {
		this.entityId = buffer.readVarInt();
		this.actionNames = buffer.readList(FriendlyByteBuf::readUtf);
		this.customAttackNames = buffer.readList(FriendlyByteBuf::readUtf);
	}

	public static void buffer(MonsterActionOpenMessage message, FriendlyByteBuf buffer) {
		buffer.writeVarInt(message.entityId);
		buffer.writeCollection(message.actionNames, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.customAttackNames, FriendlyByteBuf::writeUtf);
	}

	public static void handler(MonsterActionOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> MonsterActionScreen.open(message.entityId, message.actionNames, message.customAttackNames));
	}
}
