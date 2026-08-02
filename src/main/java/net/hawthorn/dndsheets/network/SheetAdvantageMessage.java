package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.SheetCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: botón de ventaja/desventaja en SheetAdjustScreen (equivalente en GUI a
///dndsheet advantage).
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class SheetAdvantageMessage {
	String targetUuid, label;

	public SheetAdvantageMessage(String targetUuid, String label) {
		this.targetUuid = targetUuid;
		this.label = label;
	}

	public SheetAdvantageMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.label = buffer.readUtf();
	}

	public static void buffer(SheetAdvantageMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.label);
	}

	public static void handler(SheetAdvantageMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			ServerPlayer target = dm.getServer().getPlayerList().getPlayer(UUID.fromString(message.targetUuid));
			if (target != null) SheetCommand.applyAdvantage(target, message.label);
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(SheetAdvantageMessage.class, SheetAdvantageMessage::buffer, SheetAdvantageMessage::new, SheetAdvantageMessage::handler);
	}
}
