package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: botón de tipo de daño/afinidad en SheetAdjustScreen (equivalente en GUI a
///dndsheet damagetype).
public class SheetDamageAffinityMessage {
	String targetUuid, damageType, affinity;

	public SheetDamageAffinityMessage(String targetUuid, String damageType, String affinity) {
		this.targetUuid = targetUuid;
		this.damageType = damageType;
		this.affinity = affinity;
	}

	public SheetDamageAffinityMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.damageType = buffer.readUtf();
		this.affinity = buffer.readUtf();
	}

	public static void buffer(SheetDamageAffinityMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.damageType);
		buffer.writeUtf(message.affinity);
	}

	public static void handler(SheetDamageAffinityMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> DndsheetsMod.withDmTarget(context, message.targetUuid,
			target -> SheetCommand.applyDamageAffinity(target, message.damageType, message.affinity)));
	}
}
