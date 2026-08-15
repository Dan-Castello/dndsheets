package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.CombatFx;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: invoca un monstruo YA CARGADO (equivalente en GUI a /dndmonsters spawn
//<id>, a diferencia de SpawnGenericMessage que crea un NPC en blanco) en la posición del propio DM, desde
//MonsterSpawnListScreen.
public class MonsterSpawnMessage {
	String monsterId;

	public MonsterSpawnMessage(String monsterId) {
		this.monsterId = monsterId;
	}

	public MonsterSpawnMessage(FriendlyByteBuf buffer) {
		this.monsterId = buffer.readUtf();
	}

	public static void buffer(MonsterSpawnMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.monsterId);
	}

	public static void handler(MonsterSpawnMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;

			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(message.monsterId);
			if (block == null) {
				dm.sendSystemMessage(Component.literal("No conozco el monstruo \"" + message.monsterId + "\"."));
				return;
			}

			Vec3 pos = dm.position();
			Entity entity = MonsterRegistry.spawnAt(dm.serverLevel(), pos.x, pos.y, pos.z, message.monsterId);
			if (entity == null) {
				dm.sendSystemMessage(Component.literal("El ítem base \"" + block.baseEntityId() + "\" de " + message.monsterId + " no existe."));
				return;
			}

			CombatFx.monsterSpawn(entity);
			dm.sendSystemMessage(Component.literal("Invocado " + block.name() + " (CA " + block.ac() + ", " + block.maxHp() + " PG)."));
		});
	}
}
