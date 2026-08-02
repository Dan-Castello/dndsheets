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

//Cliente (el DM) -> servidor: botón de tipo de daño/afinidad en SheetAdjustScreen (equivalente en GUI a
///dndsheet damagetype).
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
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
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			ServerPlayer target = dm.getServer().getPlayerList().getPlayer(UUID.fromString(message.targetUuid));
			if (target != null) SheetCommand.applyDamageAffinity(target, message.damageType, message.affinity);
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(SheetDamageAffinityMessage.class, SheetDamageAffinityMessage::buffer, SheetDamageAffinityMessage::new, SheetDamageAffinityMessage::handler);
	}
}
