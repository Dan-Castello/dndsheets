package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: botón de ventaja/desventaja en SheetAdjustScreen (equivalente en GUI a
///dndsheet advantage).
public class SheetAdvantageMessage {
	String targetUuid, label;

	public SheetAdvantageMessage(String targetUuid, String label) {
		this.targetUuid = targetUuid;
		this.label = label;
	}

	public SheetAdvantageMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.label = buffer.readUtf();
	}

	public static void buffer(SheetAdvantageMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.label);
	}

	public static void handler(SheetAdvantageMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DndsheetsMod.withDmTarget(context, message.targetUuid,
			target -> SheetCommand.applyAdvantage(target, message.label)));
		context.setPacketHandled(true);
	}
}
