package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>Dos ítems de comodidad para no depender del DM tecleando {@code /dndturns} cada vez: un jugador
 * con el turno puede pasarlo él mismo ({@code {dndsheets:{turnNext:true}}}, Brújula) o deshacer su
 * propia acción para elegir de nuevo sin perder el turno ({@code {dndsheets:{turnUndo:true}}}, Añico de
 * Eco). Ambos solo funcionan para quien tiene el turno ahora mismo — ver {@link TurnManager#isCurrentActor}.</p>
 */
public class TurnItemManager {

	//Se activa desde AbilityItemDispatcher en vez de suscribirse a los 3 eventos de interacción por
	//separado — ver AUDIT_TECHNICAL.md M-EVT-1.
	static void tryUse(PlayerInteractEvent event, boolean isNext) {
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
		return AbilityItem.build(ItemLook.TURN_NEXT, "turnNext", Component.literal("Fin de Turno"),
			Component.literal("Clic derecho en tu turno: pasa al siguiente combatiente.").withStyle(ChatFormatting.GRAY));
	}

	public static ItemStack buildUndoTurnStack() {
		return AbilityItem.build(ItemLook.TURN_UNDO, "turnUndo", Component.literal("Deshacer Turno"),
			Component.literal("Clic derecho en tu turno: deshace tu acción y elige de nuevo.").withStyle(ChatFormatting.GRAY));
	}
}
