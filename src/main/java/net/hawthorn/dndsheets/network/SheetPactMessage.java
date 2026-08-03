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

//Cliente (el DM) -> servidor: botón de pacto del brujo en SheetAdjustScreen (equivalente en GUI a
///dndsheet pact — ver AUDIT_UX.md, DM #3: antes de esto solo existía como comando tecleado a mano).
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class SheetPactMessage {
	String targetUuid, pacto;

	public SheetPactMessage(String targetUuid, String pacto) {
		this.targetUuid = targetUuid;
		this.pacto = pacto;
	}

	public SheetPactMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.pacto = buffer.readUtf();
	}

	public static void buffer(SheetPactMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.pacto);
	}

	public static void handler(SheetPactMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			ServerPlayer target = dm.getServer().getPlayerList().getPlayer(UUID.fromString(message.targetUuid));
			if (target != null) SheetCommand.applyPact(target, message.pacto);
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(SheetPactMessage.class, SheetPactMessage::buffer, SheetPactMessage::new, SheetPactMessage::handler);
	}
}
