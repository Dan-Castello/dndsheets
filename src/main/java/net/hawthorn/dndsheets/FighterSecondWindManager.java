package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
 * <p>Segundo Aliento del guerrero: cura {@code 1d10 + nivel} una vez por descanso (corto o largo — ver
 * {@link RestManager#applyRest}, que llama a {@link #resetOnRest} en los dos casos, igual que en 5e de
 * verdad). Sin duración que contar en asaltos ni en ticks — a diferencia de la Furia, esto es un simple
 * "usado/no usado" que un descanso resetea, así que no necesita nada de {@link TurnManager}.</p>
 */
@Mod.EventBusSubscriber
public class FighterSecondWindManager {
	private static final Set<UUID> used = ConcurrentHashMap.newKeySet();

	public static void use(ServerPlayer player) {
		if (!used.add(player.getUUID())) {
			player.sendSystemMessage(Component.literal("Ya usaste Segundo Aliento. Recupéralo descansando.").withStyle(ChatFormatting.GRAY));
			return;
		}

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		int level = SheetLoader.characterLevelOf(sheet, player);
		DiceManager.RollOutcome heal = DiceManager.roll(sheet != null ? sheet : new JsonObject(), "1d10 + " + level);
		int amount = heal.result() != null ? heal.result().getValue() : level;

		player.heal(amount);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.literal("¡Segundo Aliento! Recuperas " + amount + " PG.").withStyle(ChatFeedback.RESOURCE));
	}

	//Público: RestManager lo llama para los dos tipos de descanso, corto y largo — 5e recupera este
	//recurso con cualquiera de los dos, a diferencia de los espacios de conjuro (solo descanso largo).
	public static void resetOnRest(ServerPlayer player) {
		used.remove(player.getUUID());
	}

	//--- Ítem de Segundo Aliento: mismo patrón que el Tótem de Furia (BarbarianRageManager) ---

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
		if (tag == null || !tag.contains("dndsheets") || !tag.getCompound("dndsheets").getBoolean("secondWind")) return;

		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) use(player);
	}

	public static ItemStack buildSecondWindStack() {
		ItemStack stack = new ItemStack(Items.GOLDEN_APPLE);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("secondWind", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Segundo Aliento"));

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.literal("Clic derecho: cura 1d10 + nivel. Una vez por descanso.").withStyle(ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);

		return stack;
	}
}
