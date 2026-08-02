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

//Cliente (el DM) -> servidor: botón Aplicar espacios de conjuro en SheetAdjustScreen (equivalente en GUI
//a /dndsheet setslots).
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class SheetSlotsMessage {
	String targetUuid;
	int max, current;

	public SheetSlotsMessage(String targetUuid, int max, int current) {
		this.targetUuid = targetUuid;
		this.max = max;
		this.current = current;
	}

	public SheetSlotsMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.max = buffer.readVarInt();
		this.current = buffer.readVarInt();
	}

	public static void buffer(SheetSlotsMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeVarInt(message.max);
		buffer.writeVarInt(message.current);
	}

	public static void handler(SheetSlotsMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			ServerPlayer target = dm.getServer().getPlayerList().getPlayer(UUID.fromString(message.targetUuid));
			if (target != null) SheetCommand.applySlots(target, message.max, message.current);
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(SheetSlotsMessage.class, SheetSlotsMessage::buffer, SheetSlotsMessage::new, SheetSlotsMessage::handler);
	}
}
