package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.command.TraitCommand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: eligió un rasgo en TraitGrantScreen, se lo concede al objetivo elegido antes
//en PlayerPickerScreen (equivalente en GUI a /dndtraits grant).
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class TraitGrantMessage {
	String targetUuid, traitId;

	public TraitGrantMessage(String targetUuid, String traitId) {
		this.targetUuid = targetUuid;
		this.traitId = traitId;
	}

	public TraitGrantMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.traitId = buffer.readUtf();
	}

	public static void buffer(TraitGrantMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.traitId);
	}

	public static void handler(TraitGrantMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;

			ServerPlayer target = dm.getServer().getPlayerList().getPlayer(UUID.fromString(message.targetUuid));
			if (target == null) return;
			TraitCommand.grantToPlayer(target, message.traitId);
		});
		context.setPacketHandled(true);
	}

	@SubscribeEvent
	public static void registerMessage(FMLCommonSetupEvent event) {
		DndsheetsMod.addNetworkMessage(TraitGrantMessage.class, TraitGrantMessage::buffer, TraitGrantMessage::new, TraitGrantMessage::handler);
	}
}
