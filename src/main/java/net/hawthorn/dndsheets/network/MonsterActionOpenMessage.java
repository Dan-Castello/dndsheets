package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.MonsterActionScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//Server -> client (the DM): opens the attack/spell menu of a monster just touched with the DM rod.
public class MonsterActionOpenMessage {
	int entityId;
	List<String> actionNames;
	List<String> customAttackNames; //Subset of actionNames added live (see MonsterRegistry.addCustomAttack): the menu offers them for editing/removal apart from the predefined ones.

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
