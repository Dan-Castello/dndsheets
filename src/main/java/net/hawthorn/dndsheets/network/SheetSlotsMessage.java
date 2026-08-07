package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: botón Aplicar espacios de conjuro en SheetAdjustScreen (equivalente en GUI
//a /dndsheet setslots).
public class SheetSlotsMessage {
	String targetUuid;
	int max, current;

	public SheetSlotsMessage(String targetUuid, int max, int current) {
		this.targetUuid = targetUuid;
		this.max = max;
		this.current = current;
	}

	public SheetSlotsMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.max = buffer.readVarInt();
		this.current = buffer.readVarInt();
	}

	public static void buffer(SheetSlotsMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeVarInt(message.max);
		buffer.writeVarInt(message.current);
	}

	public static void handler(SheetSlotsMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DndsheetsMod.withDmTarget(context, message.targetUuid,
			target -> SheetCommand.applySlots(target, message.max, message.current)));
		context.setPacketHandled(true);
	}
}
