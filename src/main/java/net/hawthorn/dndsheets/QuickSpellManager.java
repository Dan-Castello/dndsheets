package net.hawthorn.dndsheets;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>Un báculo (o cualquier ítem) etiquetado {@code {dndsheets:{quickSpell:"id"}}} (ver
 * {@code /dndspells staff}) lanza ese hechizo de un clic derecho, sin pasar por el Grimorio: usa las
 * mismas características/espacios de conjuro del portador que un lanzado normal, apuntando a lo que
 * esté mirando (ver {@link SpellCastManager}). Se engancha a los tres eventos de "clic derecho" de
 * Minecraft (ítem al aire, bloque, entidad) porque cuál de los tres dispara depende de qué haya delante
 * del jugador, y el báculo debe funcionar igual en los tres casos.</p>
 */
@Mod.EventBusSubscriber
public class QuickSpellManager {

	@SubscribeEvent
	public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		tryQuickCast(event, event.getItemStack());
	}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		tryQuickCast(event, event.getItemStack());
	}

	@SubscribeEvent
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		tryQuickCast(event, event.getItemStack());
	}

	private static void tryQuickCast(PlayerInteractEvent event, ItemStack heldItem) {
		if (event.getEntity().level().isClientSide()) return;
		String spellId = SpellRegistry.quickSpellIdOf(heldItem);
		if (spellId == null) return;

		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) {
			SpellCastManager.handleCastRequest(player, spellId);
		}
	}
}
