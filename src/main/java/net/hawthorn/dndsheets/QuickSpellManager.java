package net.hawthorn.dndsheets;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>Un báculo (o cualquier ítem) etiquetado {@code {dndsheets:{quickSpell:"id"}}} (ver
 * {@code /dndspells staff}) lanza ese hechizo de un clic derecho, sin pasar por el Grimorio: usa las
 * mismas características/espacios de conjuro del portador que un lanzado normal, apuntando a lo que
 * esté mirando (ver {@link SpellCastManager}). Se engancha a los tres eventos de "clic derecho" de
 * Minecraft (ítem al aire, bloque, entidad) porque cuál de los tres dispara depende de qué haya delante
 * del jugador, y el báculo debe funcionar igual en los tres casos.</p>
 *
 * <p>Agachado + clic con un báculo de área: en vez de lanzar, previsualiza dónde caería el radio
 * ({@link SpellCastManager#previewAoe}) — de pie, el clic sigue lanzando de verdad como siempre.</p>
 */
public class QuickSpellManager {

	//Se activa desde AbilityItemDispatcher en vez de suscribirse a los 3 eventos de interacción por
	//separado — ver AUDIT_TECHNICAL.md M-EVT-1. A diferencia de los demás ítems de un solo flag booleano,
	//el dispatcher detecta este por SpellRegistry.quickSpellIdOf (equivalente a dndTag.contains("quickSpell"))
	//y ya trae el id extraído.
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
