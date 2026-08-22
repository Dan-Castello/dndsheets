package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

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
public class SorcererMetamagicManager {

	//Se activa desde AbilityItemDispatcher en vez de suscribirse a RightClickItem por su cuenta.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("twinnedSpellPending", true);
		SheetLoader.saveAndSync(player, sheet);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.resource.twinned_armed").withStyle(ChatFeedback.RESOURCE));
	}

	//Público: SpellCastManager lo consume al lanzar el siguiente hechizo, con o sin segundo objetivo real
	//cerca — se gasta igual, tal como en 5e gastas el punto de hechicero aunque no haya nadie más a mano.
	public static boolean consumePending(JsonObject sheet) {
		if (sheet == null || !sheet.has("twinnedSpellPending") || !sheet.get("twinnedSpellPending").getAsBoolean()) return false;
		sheet.remove("twinnedSpellPending");
		return true;
	}

	public static ItemStack buildTwinnedSpellStack() {
		return AbilityItem.build(ItemLook.TWINNED, "twinnedSpell", Component.translatable("chat.dndsheets.metamagic.item_name"),
			Component.translatable("chat.dndsheets.metamagic.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
