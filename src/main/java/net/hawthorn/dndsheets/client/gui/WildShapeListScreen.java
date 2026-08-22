package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.DruidWildShapeManager;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.hawthorn.dndsheets.network.WildShapeMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * <p>En qué bestia se convierte el druida. La lista no es una tabla escrita a mano: es el bestiario
 * filtrado por tipo de criatura ({@code CreatureType.BEAST}), así que un pack que añada bestias las
 * ofrece aquí sin tocar nada.</p>
 *
 * <p>Cada fila enseña PG y CA, que es lo único que de verdad se compara al elegir forma — un lobo y un
 * oso pardo no se diferencian por el nombre cuando lo que decides es si aguantas el siguiente turno.</p>
 */
public class WildShapeListScreen extends ListPickerScreen {

	private WildShapeListScreen() {
		super(Component.translatable("gui.dndsheets.wildshape.title"));
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new WildShapeListScreen());
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
		for (String beastId : DruidWildShapeManager.beastIds()) {
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(beastId);
			if (block == null) continue;
			addRow(Component.translatable("gui.dndsheets.wildshape.row", block.name(), block.maxHp(), block.ac()),
				b -> {
					DndsheetsMod.PACKET_HANDLER.sendToServer(
						new WildShapeMessage(WildShapeMessage.Kind.CHOOSE, Minecraft.getInstance().player.getUUID(), beastId));
					this.onClose();
				});
		}
	}

	@Override
	protected Component emptyMessage() {
		return DruidWildShapeManager.beastIds().isEmpty()
			? Component.translatable("gui.dndsheets.wildshape.empty") : null;
	}
}
