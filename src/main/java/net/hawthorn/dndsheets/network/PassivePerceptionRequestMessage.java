package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Client (the DM) -> server: "Passive Perception" button in SheetAdjustScreen (GUI equivalent of
///dndsheet passive). The response is a private chat message to the DM; there's no need to open anything
//on the client or send a full sheet back.
public class PassivePerceptionRequestMessage {
	String targetUuid;

	public PassivePerceptionRequestMessage(String targetUuid) {
		this.targetUuid = targetUuid;
	}

	public PassivePerceptionRequestMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
	}

	public static void buffer(PassivePerceptionRequestMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
	}

	public static void handler(PassivePerceptionRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> DndsheetsMod.withDmTarget(context, message.targetUuid, target -> {
			ServerPlayer dm = context.getSender();
			int passive = SheetCommand.passivePerceptionOf(target);
			String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(target.getStringUUID()), target);
			dm.sendSystemMessage(Component.translatable("chat.dndsheets.character.passive_perception", name, passive));
		}));
	}
}
