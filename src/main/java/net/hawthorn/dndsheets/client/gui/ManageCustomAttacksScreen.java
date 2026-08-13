package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.ClearCustomAttacksMessage;
import net.hawthorn.dndsheets.network.RemoveCustomAttackMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Lista los ataques personalizados de UN monstruo ya invocado (ver
 * {@link net.hawthorn.dndsheets.MonsterRegistry#customAttacksOf}); pulsar uno lo quita al instante. Los
 * predefinidos del bloque de estadísticas de su especie no aparecen aquí — esos se editan por JSON.</p>
 */
public class ManageCustomAttacksScreen extends ListPickerScreen {
	private final int entityId;
	private final List<String> customAttackNames;

	private ManageCustomAttacksScreen(int entityId, List<String> customAttackNames) {
		super(Component.literal("Ataques personalizados"));
		this.entityId = entityId;
		this.customAttackNames = customAttackNames;
	}

	public static void open(int entityId, List<String> customAttackNames) {
		Minecraft.getInstance().setScreen(new ManageCustomAttacksScreen(entityId, customAttackNames));
	}

	@Override
	protected void buildRows() {
		for (String name : customAttackNames) {
			addRow(Component.literal("Quitar: " + name), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new RemoveCustomAttackMessage(entityId, name));
				this.onClose();
			});
		}

		addRow(Component.literal("Borrar todos"), b -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new ClearCustomAttacksMessage(entityId));
			this.onClose();
		});
	}

	@Override
	protected Component emptyMessage() {
		return customAttackNames.isEmpty() ? Component.literal("Este monstruo no tiene ataques personalizados.") : null;
	}
}
