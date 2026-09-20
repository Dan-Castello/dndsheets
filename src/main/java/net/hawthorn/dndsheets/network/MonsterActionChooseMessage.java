package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.MonsterActionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Client (the DM) -> server: chose an action from the menu for a specific monster, and who to target it
//at (chosen in PlayerPickerScreen right after; empty targetUuid = let the server fall back to the nearest one).
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
		//Same lock already used by MonsterActionManager.onInteractWithMonster: the client can send this
		//message without ever having opened the real menu (no DM Rod, not nearby), so the permission is
		//always rechecked on the server, not just whether the GUI managed to open.
		NetworkUtil.handleOnServerAsDm(context, dm ->
			MonsterActionManager.resolveAction(dm, message.entityId, message.actionIndex, message.targetUuid));
	}
}
