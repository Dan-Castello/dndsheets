package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.PresetManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

//Cliente -> servidor: el jugador pulsó "Presets" en su hoja, pide la lista de presets cargados.
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class PresetListRequestMessage {
	public PresetListRequestMessage() {}

	public PresetListRequestMessage(FriendlyByteBuf buffer) {}

	public static void buffer(PresetListRequestMessage message, FriendlyByteBuf buffer) {}

	public static void handler(PresetListRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer player = context.getSender();
			if (player == null) return;
			List<String> ids = PresetManager.presetIds();
			List<String> names = PresetManager.presetNames(ids);
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new PresetListMessage(ids, names));
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(PresetListRequestMessage.class, PresetListRequestMessage::buffer, PresetListRequestMessage::new, PresetListRequestMessage::handler);
	}
}
