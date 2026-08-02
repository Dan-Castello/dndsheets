package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Furia del bárbaro: resistencia a daño físico (cortante/perforante/contundente/físico) y +{@value
 * #DAMAGE_BONUS} al daño de armas cuerpo a cuerpo con Fuerza, durante {@value #DURATION_ROUNDS} asaltos
 * (1 minuto real de 5e). Se activa con clic derecho en el Tótem de Furia
 * ({@code {dndsheets:{rage:true}}}, entregado con {@code /dndsheet rageitem}), igual de patrón que los
 * ítems de turno ({@link TurnItemManager}).</p>
 *
 * <p><b>Duración por asaltos, no solo por ticks reales</b>: si el modo turnos está activo cuando se activa
 * la Furia, la duración se cuenta en asaltos completos ({@link TurnManager#onRoundsPass}) — un combate
 * lento en tiempo real no debería "gastar" la Furia antes de tiempo, ni un combate rápido dejarla parada
 * de más. Fuera de modo turnos (juego libre, sin iniciativa), cae a temporizador real
 * ({@link DndsheetsMod#queueServerWork}). Se decide UNA vez, al activarse; si el modo turnos se
 * activa/desactiva a mitad de la Furia, no cambia de modo de conteo (simplificación deliberada).</p>
 *
 * <p><b>Otra simplificación deliberada</b>: en 5e de verdad la Furia tiene un número limitado de usos por
 * descanso largo (2 a nivel 1-2, más en niveles altos). Aquí no hay límite de usos — activar de nuevo con
 * la Furia ya puesta no hace nada raro, solo no reinicia el contador.</p>
 */
@Mod.EventBusSubscriber
public class BarbarianRageManager {
	private static final int DURATION_ROUNDS = 10; //1 minuto de 5e = 10 asaltos.
	private static final int DURATION_TICKS = 20 * 60; //1 minuto real fuera de modo turnos.
	public static final int DAMAGE_BONUS = 2; //Bono de Furia a nivel 1-8 (sube a 3/4 en niveles altos en 5e; simplificado a un valor fijo).

	private static final Set<UUID> raging = ConcurrentHashMap.newKeySet();

	public static boolean isRaging(ServerPlayer player) {
		return raging.contains(player.getUUID());
	}

	public static void activate(ServerPlayer player) {
		if (!raging.add(player.getUUID())) return; //Ya estaba en furia: no reinicia el contador ni duplica el mensaje.

		UUID uuid = player.getUUID();
		MinecraftServer server = player.getServer();
		Runnable expire = () -> {
			if (raging.remove(uuid) && server != null) {
				ServerPlayer stillHere = server.getPlayerList().getPlayer(uuid);
				if (stillHere != null) stillHere.sendSystemMessage(Component.literal("Tu furia termina.").withStyle(ChatFormatting.GRAY));
			}
		};

		if (TurnManager.isActive()) {
			TurnManager.onRoundsPass(DURATION_ROUNDS, expire);
		} else {
			DndsheetsMod.queueServerWork(DURATION_TICKS, expire);
		}

		CombatFx.activate(player);
		player.sendSystemMessage(Component.literal("¡Entras en furia! Resistencia a daño físico y +" + DAMAGE_BONUS + " de daño cuerpo a cuerpo con Fuerza.").withStyle(ChatFeedback.RESOURCE));
	}

	//--- Tótem de Furia: mismo patrón que los ítems de turno (TurnItemManager) ---

	@SubscribeEvent
	public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		tryUse(event, event.getItemStack());
	}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		tryUse(event, event.getItemStack());
	}

	@SubscribeEvent
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		tryUse(event, event.getItemStack());
	}

	private static void tryUse(PlayerInteractEvent event, ItemStack stack) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets") || !tag.getCompound("dndsheets").getBoolean("rage")) return;

		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) activate(player);
	}

	public static ItemStack buildRageItemStack() {
		ItemStack stack = new ItemStack(Items.RED_DYE);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("rage", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Tótem de Furia"));

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.literal("Clic derecho: entra en furia " + DURATION_ROUNDS + " asaltos.").withStyle(ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);

		return stack;
	}
}
