package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: botón "Percepción pasiva" en SheetAdjustScreen (equivalente en GUI a
///dndsheet passive). La respuesta es un mensaje de chat privado al DM, no hace falta abrir nada en el
//cliente ni mandar una hoja completa de vuelta.
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class PassivePerceptionRequestMessage {
	String targetUuid;

	public PassivePerceptionRequestMessage(String targetUuid) {
		this.targetUuid = targetUuid;
	}

	public PassivePerceptionRequestMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
	}

	public static void buffer(PassivePerceptionRequestMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
	}

	public static void handler(PassivePerceptionRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			ServerPlayer target = dm.getServer().getPlayerList().getPlayer(UUID.fromString(message.targetUuid));
			if (target == null) return;

			int passive = SheetCommand.passivePerceptionOf(target);
			String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(target.getStringUUID()), target);
			dm.sendSystemMessage(Component.literal("Percepción pasiva de " + name + ": " + passive));
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(PassivePerceptionRequestMessage.class, PassivePerceptionRequestMessage::buffer, PassivePerceptionRequestMessage::new, PassivePerceptionRequestMessage::handler);
	}
}
