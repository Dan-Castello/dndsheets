package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.Condition;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.SheetAdjustMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * <p>Las 14 condiciones de 5e de un jugador, desde el Panel de DM: cada fila muestra si la tiene puesta
 * y al pulsarla la alterna. Una sola lista para los dos sentidos en vez de un "aplicar" y un "quitar"
 * separados — el DM ve el estado real y actúa sobre él, que era justo lo que faltaba: sin esto, las
 * condiciones solo se podían tocar por {@code /dndturns effect} y sin forma de consultarlas.</p>
 *
 * <p>El estado de partida llega en el mismo {@code SheetSummaryMessage} que ya traía oro/PG/CA al abrir
 * {@link SheetAdjustScreen}, así que no hace falta ni un mensaje nuevo ni una ida y vuelta extra. Se
 * mantiene en local al alternar en vez de volver a pedirlo: el servidor es la autoridad, pero para
 * repintar una marca de verificación no vale la pena un viaje de red por clic.</p>
 */
public class ConditionListScreen extends ListPickerScreen {

	private final String targetUuid;
	private final Set<Condition> active;

	private ConditionListScreen(String targetUuid, String targetName, Set<Condition> active, Screen parent) {
		super(Component.translatable("gui.dndsheets.condition_list.title", targetName), parent);
		this.targetUuid = targetUuid;
		this.active = active;
	}

	/** @param conditionsCsv etiquetas separadas por coma, tal cual viajan en {@code SheetSummaryMessage}. */
	public static void open(String targetUuid, String targetName, String conditionsCsv) {
		Set<Condition> active = EnumSet.noneOf(Condition.class);
		if (conditionsCsv != null && !conditionsCsv.isEmpty()) {
			for (String label : conditionsCsv.split(",")) {
				Condition condition = Condition.fromLabel(label.trim());
				if (condition != null) active.add(condition);
			}
		}
		Minecraft.getInstance().setScreen(new ConditionListScreen(targetUuid, targetName, active, Minecraft.getInstance().screen));
	}

	//Sin buscador a propósito, aunque la base lo ofrezca: son 14 filas fijas, y alternar una reconstruye la
	//pantalla entera, lo que vaciaría la caja de búsqueda en cada clic. Buscar entre 14 no compensa eso.
	@Override
	protected void buildRows() {
		for (Condition condition : Condition.values()) {
			addRow(rowLabel(condition), button -> toggle(condition));
		}
	}

	private Component rowLabel(Condition condition) {
		boolean on = active.contains(condition);
		return Component.literal((on ? "✔ " : "  ") + condition.label())
			.withStyle(on ? ChatFormatting.RED : ChatFormatting.GRAY);
	}

	private void toggle(Condition condition) {
		boolean apply = !active.contains(condition);
		if (apply) active.add(condition);
		else active.remove(condition);
		DndsheetsMod.PACKET_HANDLER.sendToServer(SheetAdjustMessage.condition(targetUuid, condition.label(), apply));
		//rebuildWidgets() de vanilla: vuelve a llamar a init(), que a su vez rellama a buildRows() con el
		//estado ya alternado. Reconstruir 14 filas para repintar una marca no necesita nada más fino.
		this.rebuildWidgets();
	}
}
