package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.TraitCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: eligió un rasgo en TraitGrantScreen, se lo concede al objetivo elegido antes
//en PlayerPickerScreen (equivalente en GUI a /dndtraits grant).
public class TraitGrantMessage {
	String targetUuid, traitId;

	public TraitGrantMessage(String targetUuid, String traitId) {
		this.targetUuid = targetUuid;
		this.traitId = traitId;
	}

	public TraitGrantMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.traitId = buffer.readUtf();
	}

	public static void buffer(TraitGrantMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.traitId);
	}

	public static void handler(TraitGrantMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DndsheetsMod.withDmTarget(context, message.targetUuid,
			target -> TraitCommand.grantToPlayer(target, message.traitId)));
		context.setPacketHandled(true);
	}
}
