package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.PresetManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: eligió un preset en PresetScreen para OTRO jugador, elegido antes en
//PlayerPickerScreen (equivalente en GUI a /dndpresets apply <jugador> <presetId> — ver AUDIT_UX.md, DM #2).
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
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
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;

			ServerPlayer target = dm.getServer().getPlayerList().getPlayer(UUID.fromString(message.targetUuid));
			if (target == null) return;
			PresetManager.applyPreset(target, message.presetId);
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(PresetApplyToMessage.class, PresetApplyToMessage::buffer, PresetApplyToMessage::new, PresetApplyToMessage::handler);
	}
}
