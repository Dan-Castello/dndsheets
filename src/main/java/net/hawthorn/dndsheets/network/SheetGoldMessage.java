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

//Cliente (el DM) -> servidor: botón Añadir/Fijar oro en SheetAdjustScreen (equivalente en GUI a
///dndsheet gold).
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class SheetGoldMessage {
	String targetUuid, mode;
	int amount;

	public SheetGoldMessage(String targetUuid, String mode, int amount) {
		this.targetUuid = targetUuid;
		this.mode = mode;
		this.amount = amount;
	}

	public SheetGoldMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.mode = buffer.readUtf();
		this.amount = buffer.readVarInt();
	}

	public static void buffer(SheetGoldMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.mode);
		buffer.writeVarInt(message.amount);
	}

	public static void handler(SheetGoldMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			ServerPlayer target = dm.getServer().getPlayerList().getPlayer(UUID.fromString(message.targetUuid));
			if (target != null) SheetCommand.applyGold(target, message.mode, message.amount);
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(SheetGoldMessage.class, SheetGoldMessage::buffer, SheetGoldMessage::new, SheetGoldMessage::handler);
	}
}
