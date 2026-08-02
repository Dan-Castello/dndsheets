package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Marca del Cazador: clic derecho del explorador sobre un objetivo (jugador o monstruo invocado) lo
 * marca durante {@value #DURATION_ROUNDS} asaltos/10 minutos reales (mismo patrón de duración por
 * asaltos-o-ticks que Furia/Inspiración Bárdica). Mientras dure, cada golpe del explorador CONTRA ESE
 * OBJETIVO concreto suma {@value #DICE} de daño extra — ver {@link CombatManager}, que la tira aparte y
 * suma el monto, igual que Ataque Furtivo, para no meter dos grupos de dados en una sola expresión.</p>
 *
 * <p><b>Simplificación deliberada</b>: en 5e de verdad esto es un hechizo de concentración (nivel 1),
 * así que golpear a otra cosa o recibir cierto daño puede acabarlo antes de tiempo. Aquí no está
 * enganchado a {@link ConcentrationManager} — dura su tiempo fijo pase lo que pase, más simple y
 * consistente con cómo esta pasada ya simplificó Forma Salvaje/Furia.</p>
 */
@Mod.EventBusSubscriber
public class RangerHunterMarkManager {
	public static final String DICE = "1d6";
	private static final int DURATION_ROUNDS = 100; //10 minutos de 5e = 100 asaltos.
	private static final int DURATION_TICKS = 20 * 60 * 10;

	private static final Map<UUID, Integer> markedEntityIdByRanger = new ConcurrentHashMap<>();

	public static boolean isMarked(ServerPlayer ranger, Entity target) {
		Integer markedId = markedEntityIdByRanger.get(ranger.getUUID());
		return markedId != null && markedId == target.getId();
	}

	public static void mark(ServerPlayer ranger, Entity target) {
		markedEntityIdByRanger.put(ranger.getUUID(), target.getId());
		CombatFx.activate(ranger);
		ranger.sendSystemMessage(Component.literal("Marcas a " + target.getName().getString() + " como presa.").withStyle(ChatFeedback.RESOURCE));

		UUID rangerUuid = ranger.getUUID();
		int targetId = target.getId();
		Runnable expire = () -> {
			Integer current = markedEntityIdByRanger.get(rangerUuid);
			if (current != null && current == targetId) markedEntityIdByRanger.remove(rangerUuid);
		};
		if (TurnManager.isActive()) {
			TurnManager.onRoundsPass(DURATION_ROUNDS, expire);
		} else {
			DndsheetsMod.queueServerWork(DURATION_TICKS, expire);
		}
	}

	@SubscribeEvent
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag tag = event.getItemStack().getTag();
		if (tag == null || !tag.contains("dndsheets") || !tag.getCompound("dndsheets").getBoolean("hunterMark")) return;
		if (!(event.getEntity() instanceof ServerPlayer ranger)) return;

		event.setCanceled(true);
		mark(ranger, event.getTarget());
	}

	public static ItemStack buildHunterMarkStack() {
		ItemStack stack = new ItemStack(Items.SPYGLASS);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("hunterMark", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Marca del Cazador"));

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.literal("Clic derecho en un objetivo: +1d6 de daño al golpearlo con armas.").withStyle(ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);

		return stack;
	}
}
