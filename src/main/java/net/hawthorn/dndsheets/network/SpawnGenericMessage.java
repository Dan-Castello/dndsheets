package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.CombatFx;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: formulario de SpawnGenericScreen (equivalente en GUI a
///dndmonsters spawn generic), invoca al DM en su propia posición.
public class SpawnGenericMessage {
	String name, baseEntity;
	int ac, hp;

	public SpawnGenericMessage(String name, String baseEntity, int ac, int hp) {
		this.name = name;
		this.baseEntity = baseEntity;
		this.ac = ac;
		this.hp = hp;
	}

	public SpawnGenericMessage(FriendlyByteBuf buffer) {
		this.name = buffer.readUtf();
		this.baseEntity = buffer.readUtf();
		this.ac = buffer.readVarInt();
		this.hp = buffer.readVarInt();
	}

	public static void buffer(SpawnGenericMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.name);
		buffer.writeUtf(message.baseEntity);
		buffer.writeVarInt(message.ac);
		buffer.writeVarInt(message.hp);
	}

	public static void handler(SpawnGenericMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> {

			ServerLevel level = dm.serverLevel();
			Vec3 pos = dm.position();
			Entity spawned = MonsterRegistry.spawnGeneric(level, pos.x, pos.y, pos.z, message.name, message.baseEntity, message.ac, message.hp);
			if (spawned != null) CombatFx.monsterSpawn(spawned);
		});
	}
}
