package net.hawthorn.dndsheets.network;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.ContentPackFile;
import net.hawthorn.dndsheets.ContentType;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: guarda un monstruo invocado (normalmente un NPC genérico ya armado con
//ataques en vivo) como plantilla en monsters/dm_created.json — ver client.gui.MonsterTemplateSaveScreen.
public class MonsterSaveTemplateMessage {
	private static final String[] ABILITIES = {"str", "dex", "con", "int", "wis", "cha"};

	int entityId;
	String id;
	String abilitiesCsv;

	public MonsterSaveTemplateMessage(int entityId, String id, String abilitiesCsv) {
		this.entityId = entityId;
		this.id = id;
		this.abilitiesCsv = abilitiesCsv;
	}

	public MonsterSaveTemplateMessage(FriendlyByteBuf buffer) {
		this.entityId = buffer.readVarInt();
		this.id = buffer.readUtf();
		this.abilitiesCsv = buffer.readUtf();
	}

	public static void buffer(MonsterSaveTemplateMessage message, FriendlyByteBuf buffer) {
		buffer.writeVarInt(message.entityId);
		buffer.writeUtf(message.id);
		buffer.writeUtf(message.abilitiesCsv);
	}

	private static Map<String, Integer> parseAbilities(String csv) {
		Map<String, Integer> abilities = new LinkedHashMap<>();
		String[] parts = csv.split(",");
		for (int i = 0; i < ABILITIES.length; i++) {
			int value = 10;
			if (i < parts.length) {
				try {
					value = Integer.parseInt(parts[i].trim());
				} catch (NumberFormatException ignored) {
					//Se queda en 10.
				}
			}
			abilities.put(ABILITIES[i], value);
		}
		return abilities;
	}

	public static void handler(MonsterSaveTemplateMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			if (message.id.isBlank()) return;

			Entity target = dm.level().getEntity(message.entityId);
			if (target == null) return;
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(target);
			if (block == null) {
				dm.sendSystemMessage(Component.literal("Ese objetivo no es un monstruo invocado por /dndmonsters."));
				return;
			}

			List<MonsterRegistry.MonsterAttack> attacks = MonsterRegistry.customAttacksOf(target);
			MonsterRegistry.MonsterStatBlock template = new MonsterRegistry.MonsterStatBlock(
				message.id, block.name(), block.baseEntityId(), block.ac(), block.maxHp(),
				parseAbilities(message.abilitiesCsv), block.proficiencyBonus(), attacks, List.of(),
				block.damageAffinities(), block.nonmagicalAffinities(), block.type(), block.legendaryResistances()); //Se heredan del monstruo capturado: la plantilla no debería perder sus resistencias ni su tipo.
			JsonObject json = MonsterRegistry.toJson(template);

			try {
				ContentPackFile.upsert(ContentType.MONSTER.dmCreatedFile(), "id", json);
				ContentType.MONSTER.load(ContentType.MONSTER.dmCreatedFile());
			} catch (IOException e) {
				dm.sendSystemMessage(Component.literal("No pude guardar la plantilla: " + e.getMessage()));
				return;
			}

			dm.sendSystemMessage(Component.literal("Plantilla \"" + message.id + "\" guardada (" + attacks.size() + " ataque(s))."));
		});
	}
}
