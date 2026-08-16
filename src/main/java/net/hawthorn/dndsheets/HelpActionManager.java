package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p><b>Ayudar</b>, la cuarta acción de turno: distraes a un enemigo o preparas la jugada de un compañero,
 * y su próximo ataque tiene ventaja.</p>
 *
 * <p>Va aparte de {@link TurnActionManager} —donde están Esquivar, Correr y Desengancharse— porque es la
 * única de las cuatro que necesita <em>a quién</em> señalar, así que su sitio natural es un ítem de clic
 * derecho sobre otro jugador y no una entrada de un menú. Es exactamente el mismo patrón que la Inspiración
 * Bárdica, que tiene el mismo problema y ya lo resolvió así.</p>
 *
 * <p>La ventaja se apunta en {@code nextAttackAdvantage}, el flag de un solo uso que la hoja ya tenía y que
 * {@code CombatManager.consumeAdvantage} gasta en la siguiente tirada de ataque, venga de un arma, de un
 * conjuro o del botón de la propia hoja. No hacía falta un mecanismo nuevo: hacía falta usar el que
 * {@code /dndsheet advantage} lleva usando desde siempre.</p>
 */
public class HelpActionManager {

	static void tryUse(PlayerInteractEvent.EntityInteract event) {
		if (!(event.getEntity() instanceof ServerPlayer helper) || !(event.getTarget() instanceof ServerPlayer ally)) return;
		InteractionEvents.consume(event);

		if (helper == ally) {
			helper.sendSystemMessage(Component.translatable("chat.dndsheets.action.help_self").withStyle(ChatFormatting.GRAY));
			return;
		}
		//Igual que las otras tres: fuera de combate no hay turno que gastar, y aceptar el clic sin decirlo
		//dejaría al jugador creyendo que ayudó.
		if (!TurnManager.isActive()) {
			helper.sendSystemMessage(Component.translatable("chat.dndsheets.action.needs_combat").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!TurnManager.tryAct(helper)) {
			TurnManager.notifyCantAct(helper);
			return;
		}

		JsonObject allySheet = SheetLoader.getServerSheet(ally.getStringUUID());
		if (allySheet == null) return;
		allySheet.addProperty("nextAttackAdvantage", "advantage");

		//El aliado tiene que VER que le llegó: es un flag en su hoja, y sin este parche solo se enteraría al
		//volver a abrirla. Mismo parche corto que usa el resto del mod tras tocar un campo suelto.
		JsonObject patch = new JsonObject();
		patch.addProperty("nextAttackAdvantage", "advantage");
		DndsheetsMod.sendSheetFieldUpdate(ally, patch);

		CombatFx.activate(ally);
		String helperName = SheetLoader.characterNameOf(SheetLoader.getServerSheet(helper.getStringUUID()), helper);
		String allyName = SheetLoader.characterNameOf(allySheet, ally);
		ChatFeedback.broadcast(helper, Component.translatable("chat.dndsheets.action.help", helperName, allyName).withStyle(ChatFeedback.RESOURCE));
	}

	public static ItemStack buildHelpStack() {
		return AbilityItem.build(Items.LEAD, "helpAction", Component.literal("Ayudar"),
			Component.literal("Clic derecho en OTRO jugador: su próximo ataque tiene ventaja. Gasta tu acción.").withStyle(ChatFormatting.GRAY));
	}
}
