package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: botón de pacto del brujo en SheetAdjustScreen (equivalente en GUI a
///dndsheet pact — ver AUDIT_UX.md, DM #3: antes de esto solo existía como comando tecleado a mano).
public class SheetPactMessage {
	String targetUuid, pacto;

	public SheetPactMessage(String targetUuid, String pacto) {
		this.targetUuid = targetUuid;
		this.pacto = pacto;
	}

	public SheetPactMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.pacto = buffer.readUtf();
	}

	public static void buffer(SheetPactMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.pacto);
	}

	public static void handler(SheetPactMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> DndsheetsMod.withDmTarget(context, message.targetUuid,
			target -> SheetCommand.applyPact(target, message.pacto)));
	}
}
