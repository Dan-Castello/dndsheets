package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>Metamagia: Hechizo Gemelo. Clic derecho en el ítem marca la SIGUIENTE tirada de hechizo del hechicero
 * (un flag de un solo uso en la hoja, mismo patrón que {@code nextAttackAdvantage}) para que también
 * alcance a un segundo objetivo válido cercano — {@link SpellCastManager#handleCastRequest} lo consume
 * justo después de resolver el hechizo contra el objetivo normal.</p>
 *
 * <p><b>Simplificaciones deliberadas</b>: en 5e de verdad cuesta puntos de hechicero (no hay reserva de
 * puntos de hechicero modelada aquí, solo el pool plano de espacios de conjuro) y solo vale con hechizos
 * que ya de por sí solo tocan a un objetivo (aquí no se comprueba explícitamente, pero un hechizo de área
 * ya reparte daño a todos los del radio, así que gemelarlo no tendría sentido — se deja sin activar el
 * flag para esos casos en {@code handleCastRequest}, ver el comentario ahí). Sin límite de usos por
 * descanso, igual que Furia/Segundo Aliento.</p>
 */
@Mod.EventBusSubscriber
public class SorcererMetamagicManager {

	@SubscribeEvent
	public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		tryUse(event, event.getItemStack());
	}

	private static void tryUse(PlayerInteractEvent event, ItemStack stack) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets") || !tag.getCompound("dndsheets").getBoolean("twinnedSpell")) return;

		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("twinnedSpellPending", true);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.literal("Tu próximo hechizo de un solo objetivo alcanzará también a un segundo objetivo cercano.").withStyle(ChatFeedback.RESOURCE));
	}

	//Público: SpellCastManager lo consume al lanzar el siguiente hechizo, con o sin segundo objetivo real
	//cerca — se gasta igual, tal como en 5e gastas el punto de hechicero aunque no haya nadie más a mano.
	public static boolean consumePending(JsonObject sheet) {
		if (sheet == null || !sheet.has("twinnedSpellPending") || !sheet.get("twinnedSpellPending").getAsBoolean()) return false;
		sheet.remove("twinnedSpellPending");
		return true;
	}

	public static ItemStack buildTwinnedSpellStack() {
		ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("twinnedSpell", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Metamagia: Hechizo Gemelo"));

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.literal("Clic derecho: tu próximo hechizo alcanza a un segundo objetivo cercano.").withStyle(net.minecraft.ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);

		return stack;
	}
}
