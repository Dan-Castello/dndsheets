package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: entrega un arma cargada a un jugador (equivalente en GUI a /dndweapons
//give), desde WeaponGiveListScreen.
public class WeaponGiveMessage {
	String targetUuid;
	String weaponId;

	public WeaponGiveMessage(String targetUuid, String weaponId) {
		this.targetUuid = targetUuid;
		this.weaponId = weaponId;
	}

	public WeaponGiveMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.weaponId = buffer.readUtf();
	}

	public static void buffer(WeaponGiveMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeUtf(message.weaponId);
	}

	public static void handler(WeaponGiveMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> {
			if (Config.weaponDefaultFor(message.weaponId) == null) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.weapon.no_such", message.weaponId));
				return;
			}

			DndsheetsMod.withDmTarget(context, message.targetUuid, target -> {
				ItemStack stack = Config.buildWeaponStack(message.weaponId, 1);
				target.getInventory().add(stack);
				target.sendSystemMessage(Component.translatable("chat.dndsheets.item.received", stack.getHoverName().getString()));
			});
		});
	}
}
