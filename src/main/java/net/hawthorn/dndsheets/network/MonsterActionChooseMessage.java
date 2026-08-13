package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.MonsterActionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: eligió una acción del menú para un monstruo concreto, y a quién apuntarla
//(elegido en PlayerPickerScreen justo después; targetUuid vacío = que el servidor caiga al más cercano).
public class MonsterActionChooseMessage {
	int entityId;
	int actionIndex;
	String targetUuid;

	public MonsterActionChooseMessage(int entityId, int actionIndex, String targetUuid) {
		this.entityId = entityId;
		this.actionIndex = actionIndex;
		this.targetUuid = targetUuid;
	}

	public MonsterActionChooseMessage(FriendlyByteBuf buffer) {
		this.entityId = buffer.readVarInt();
		this.actionIndex = buffer.readVarInt();
		this.targetUuid = buffer.readUtf();
	}

	public static void buffer(MonsterActionChooseMessage message, FriendlyByteBuf buffer) {
		buffer.writeVarInt(message.entityId);
		buffer.writeVarInt(message.actionIndex);
		buffer.writeUtf(message.targetUuid);
	}

	public static void handler(MonsterActionChooseMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			//Mismo candado que ya usa MonsterActionManager.onInteractWithMonster: el cliente puede mandar
			//este mensaje sin haber abierto el menú real (sin Vara de DM, sin estar cerca), así que el
			//permiso se revisa siempre en el servidor, no solo en si la GUI llegó a abrirse.
			if (dm != null && dm.hasPermissions(2)) MonsterActionManager.resolveAction(dm, message.entityId, message.actionIndex, message.targetUuid);
		});
	}
}
