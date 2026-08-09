package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: añadió un ataque personalizado a un monstruo ya invocado desde
//AddMonsterAttackScreen (equivalente en GUI a /dndmonsters attack add).
public class AddCustomAttackMessage {
	int entityId;
	String name, toHitAbility, dice, damageAbility, damageType;

	public AddCustomAttackMessage(int entityId, String name, String toHitAbility, String dice, String damageAbility, String damageType) {
		this.entityId = entityId;
		this.name = name;
		this.toHitAbility = toHitAbility;
		this.dice = dice;
		this.damageAbility = damageAbility;
		this.damageType = damageType;
	}

	public AddCustomAttackMessage(FriendlyByteBuf buffer) {
		this.entityId = buffer.readVarInt();
		this.name = buffer.readUtf();
		this.toHitAbility = buffer.readUtf();
		this.dice = buffer.readUtf();
		this.damageAbility = buffer.readUtf();
		this.damageType = buffer.readUtf();
	}

	public static void buffer(AddCustomAttackMessage message, FriendlyByteBuf buffer) {
		buffer.writeVarInt(message.entityId);
		buffer.writeUtf(message.name);
		buffer.writeUtf(message.toHitAbility);
		buffer.writeUtf(message.dice);
		buffer.writeUtf(message.damageAbility);
		buffer.writeUtf(message.damageType);
	}

	public static void handler(AddCustomAttackMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;

			Entity target = dm.level().getEntity(message.entityId);
			if (target == null || MonsterRegistry.statBlockOf(target) == null) return;

			MonsterRegistry.addCustomAttack(target, new MonsterRegistry.MonsterAttack(
				message.name, message.toHitAbility, message.dice, message.damageAbility, message.damageType, null, null, 0));
		});
	}
}
