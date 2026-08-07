package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.PresetManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: eligió un preset en PresetScreen para OTRO jugador, elegido antes en
//PlayerPickerScreen (equivalente en GUI a /dndpresets apply <jugador> <presetId> — ver AUDIT_UX.md, DM #2).
public class PresetApplyToMessage {
	String targetUuid, presetId;

	public PresetApplyToMessage(String targetUuid, String presetId) {
		this.targetUuid = targetUuid;
		this.presetId = presetId;
	}

	public PresetApplyToMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.presetId = buffer.readUtf();
	}

	public static void buffer(PresetApplyToMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.presetId);
	}

	public static void handler(PresetApplyToMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> DndsheetsMod.withDmTarget(context, message.targetUuid,
			target -> PresetManager.applyPreset(target, message.presetId)));
		context.setPacketHandled(true);
	}
}
