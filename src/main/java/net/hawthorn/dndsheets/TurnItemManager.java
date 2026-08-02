package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>Dos ítems de comodidad para no depender del DM tecleando {@code /dndturns} cada vez: un jugador
 * con el turno puede pasarlo él mismo ({@code {dndsheets:{turnNext:true}}}, Brújula) o deshacer su
 * propia acción para elegir de nuevo sin perder el turno ({@code {dndsheets:{turnUndo:true}}}, Añico de
 * Eco). Ambos solo funcionan para quien tiene el turno ahora mismo — ver {@link TurnManager#isCurrentActor}.</p>
 */
@Mod.EventBusSubscriber
public class TurnItemManager {

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
		if (tag == null || !tag.contains("dndsheets")) return;
		CompoundTag dndTag = tag.getCompound("dndsheets");
		boolean isNext = dndTag.getBoolean("turnNext");
		boolean isUndo = dndTag.getBoolean("turnUndo");
		if (!isNext && !isUndo) return;

		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getEntity().level() instanceof ServerLevel level)) return;

		if (!TurnManager.isActive()) {
			player.sendSystemMessage(Component.literal("No hay modo turnos activo.").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!TurnManager.isCurrentActor(player)) {
			player.sendSystemMessage(Component.literal("No es tu turno.").withStyle(ChatFormatting.RED));
			return;
		}

		if (isNext) {
			TurnManager.next(level);
		} else {
			TurnManager.undoAction(level, player);
		}
	}

	public static ItemStack buildNextTurnStack() {
		ItemStack stack = new ItemStack(Items.COMPASS);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("turnNext", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Fin de Turno"));
		addLore(stack, "Clic derecho en tu turno: pasa al siguiente combatiente.");
		return stack;
	}

	public static ItemStack buildUndoTurnStack() {
		ItemStack stack = new ItemStack(Items.ECHO_SHARD);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("turnUndo", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Deshacer Turno"));
		addLore(stack, "Clic derecho en tu turno: deshace tu acción y elige de nuevo.");
		return stack;
	}

	private static void addLore(ItemStack stack, String text) {
		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(Component.literal(text).withStyle(ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);
	}
}
