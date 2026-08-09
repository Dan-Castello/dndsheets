package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: botón de nivel de personaje en SheetAdjustScreen (equivalente en GUI a
///dndsheet setlevel — ver AUDIT_UX.md, DM #3: antes de esto solo existía como comando tecleado a mano).
public class SheetLevelMessage {
	String targetUuid;
	int nivel;

	public SheetLevelMessage(String targetUuid, int nivel) {
		this.targetUuid = targetUuid;
		this.nivel = nivel;
	}

	public SheetLevelMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.nivel = buffer.readVarInt();
	}

	public static void buffer(SheetLevelMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeVarInt(message.nivel);
	}

	public static void handler(SheetLevelMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> DndsheetsMod.withDmTarget(context, message.targetUuid,
			target -> SheetCommand.applyLevel(target, Math.max(1, Math.min(20, message.nivel)))));
	}
}
