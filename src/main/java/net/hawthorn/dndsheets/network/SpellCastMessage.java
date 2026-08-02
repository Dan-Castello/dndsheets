package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SpellCastManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente -> servidor: el jugador pulsó "Lanzar" sobre un hechizo conocido en su Grimorio.
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class SpellCastMessage {
	String spellId;

	public SpellCastMessage(String spellId) {
		this.spellId = spellId;
	}

	public SpellCastMessage(FriendlyByteBuf buffer) {
		this.spellId = buffer.readUtf();
	}

	public static void buffer(SpellCastMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.spellId);
	}

	public static void handler(SpellCastMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer player = context.getSender();
			if (player != null) SpellCastManager.handleCastRequest(player, message.spellId);
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(SpellCastMessage.class, SpellCastMessage::buffer, SpellCastMessage::new, SpellCastMessage::handler);
	}
}
