package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: botón Añadir/Fijar oro en SheetAdjustScreen (equivalente en GUI a
///dndsheet gold).
public class SheetGoldMessage {
	String targetUuid, mode;
	int amount;

	public SheetGoldMessage(String targetUuid, String mode, int amount) {
		this.targetUuid = targetUuid;
		this.mode = mode;
		this.amount = amount;
	}

	public SheetGoldMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.mode = buffer.readUtf();
		this.amount = buffer.readVarInt();
	}

	public static void buffer(SheetGoldMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.mode);
		buffer.writeVarInt(message.amount);
	}

	public static void handler(SheetGoldMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> DndsheetsMod.withDmTarget(context, message.targetUuid,
			target -> SheetCommand.applyGold(target, message.mode, message.amount)));
	}
}
