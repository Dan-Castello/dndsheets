package net.hawthorn.dndsheets;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>A staff (or any item) tagged {@code {dndsheets:{quickSpell:"id"}}} (see
 * {@code /dndspells staff}) casts that spell with a right-click, bypassing the Spellbook: it uses the
 * same caster ability scores/spell slots as a normal cast, targeting whatever they're looking at (see
 * {@link SpellCastManager}). It hooks into all three of Minecraft's "right-click" events (item-in-air,
 * block, entity) because which of the three fires depends on what's in front of the player, and the
 * staff must work the same way in all three cases.</p>
 *
 * <p>Sneaking + clicking with an area or zone staff: instead of casting, it previews where it would land
 * ({@link SpellCastManager#previewAoe}) — standing up, the click still casts for real as always.</p>
 */
public class QuickSpellManager {

	//Triggered from AbilityItemDispatcher instead of subscribing to the 3 interaction events
	//separately. Unlike the other single-boolean-flag items,
	//the dispatcher detects this one via SpellRegistry.quickSpellIdOf (equivalent to dndTag.contains("quickSpell"))
	//and already arrives with the id extracted.
	static void tryUse(PlayerInteractEvent event, String spellId) {
		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) {
			if (player.isShiftKeyDown()) {
				SpellCastManager.previewAoe(player, spellId);
			} else {
				SpellCastManager.handleCastRequest(player, spellId);
			}
		}
	}
}
