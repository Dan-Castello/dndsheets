package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.WildShapeMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>En qué bestia se convierte el druida. Las filas no se arman leyendo el bestiario en el cliente
 * —el registro en memoria vive solo en el servidor, y un cliente que sea un proceso aparte (cualquiera
 * que no sea quien abrió el mundo) lo vería siempre vacío— sino con los datos que ya trae el propio
 * mensaje {@code OPEN_PICKER} desde {@code WildShapeWatcher.openPicker}.</p>
 *
 * <p>Cada fila enseña PG y CA, que es lo único que de verdad se compara al elegir forma — un lobo y un
 * oso pardo no se diferencian por el nombre cuando lo que decides es si aguantas el siguiente turno.</p>
 */
public class WildShapeListScreen extends ListPickerScreen {

	private final List<String> beastIds;
	private final List<String> beastNames;
	private final List<Integer> beastHps;
	private final List<Integer> beastAcs;

	private WildShapeListScreen(List<String> beastIds, List<String> beastNames, List<Integer> beastHps, List<Integer> beastAcs) {
		super(Component.translatable("gui.dndsheets.wildshape.title"));
		this.beastIds = beastIds;
		this.beastNames = beastNames;
		this.beastHps = beastHps;
		this.beastAcs = beastAcs;
	}

	public static void open(List<String> beastIds, List<String> beastNames, List<Integer> beastHps, List<Integer> beastAcs) {
		Minecraft.getInstance().setScreen(new WildShapeListScreen(beastIds, beastNames, beastHps, beastAcs));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	//Nombre + PG + CA no cabe en el ancho estándar sin cortarse, igual que le pasaba a la lista de grupo.
	@Override
	protected int buttonWidth() {
		return 260;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < beastIds.size(); i++) {
			String beastId = beastIds.get(i);
			addRow(Component.translatable("gui.dndsheets.wildshape.row", net.hawthorn.dndsheets.ContentNames.of(beastNames.get(i)), beastHps.get(i), beastAcs.get(i)),
				b -> {
					DndsheetsMod.PACKET_HANDLER.sendToServer(
						new WildShapeMessage(WildShapeMessage.Kind.CHOOSE, Minecraft.getInstance().player.getUUID(), beastId));
					this.onClose();
				});
		}
	}

	@Override
	protected Component emptyMessage() {
		return beastIds.isEmpty() ? Component.translatable("gui.dndsheets.wildshape.empty") : null;
	}
}
