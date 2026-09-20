package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.GiveableItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Client (the DM) -> server: gives one of GiveableItem's "fixed" items to a player, from
//GiveItemListScreen (GUI equivalent of /dndsheet restkit|rageitem|..., /dndmonsters dmtool|movetool,
///dndnotes give).
public class GiveItemMessage {
	GiveableItem kind;
	String targetUuid;

	public GiveItemMessage(GiveableItem kind, String targetUuid) {
		this.kind = kind;
		this.targetUuid = targetUuid;
	}

	public GiveItemMessage(FriendlyByteBuf buffer) {
		this.kind = buffer.readEnum(GiveableItem.class);
		this.targetUuid = buffer.readUtf();
	}

	public static void buffer(GiveItemMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.kind);
		buffer.writeUtf(message.targetUuid);
	}

	public static void handler(GiveItemMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> DndsheetsMod.withDmTarget(context, message.targetUuid, target -> {
			for (ItemStack stack : message.kind.stacks()) target.getInventory().add(stack);
			target.sendSystemMessage(Component.translatable("chat.dndsheets.item.received", Component.translatable(message.kind.label())));
		}));
	}
}
