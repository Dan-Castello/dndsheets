package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.ClearCustomAttacksMessage;
import net.hawthorn.dndsheets.network.RemoveCustomAttackMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Lists the custom attacks of ONE already-summoned monster (see
 * {@link net.hawthorn.dndsheets.MonsterRegistry#customAttacksOf}); clicking one removes it instantly.
 * The predefined ones from its species' stat block don't appear here — those are edited via JSON.</p>
 */
public class ManageCustomAttacksScreen extends ListPickerScreen {
	private final int entityId;
	private final List<String> customAttackNames;

	private ManageCustomAttacksScreen(int entityId, List<String> customAttackNames, Screen parent) {
		super(Component.translatable("gui.dndsheets.custom_attacks.title"), parent);
		this.entityId = entityId;
		this.customAttackNames = customAttackNames;
	}

	public static void open(int entityId, List<String> customAttackNames) {
		Minecraft.getInstance().setScreen(new ManageCustomAttacksScreen(entityId, customAttackNames, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String name : customAttackNames) {
			addRow(Component.translatable("gui.dndsheets.custom_attacks.remove", name), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new RemoveCustomAttackMessage(entityId, name));
				this.onClose();
			});
		}

		addRow(Component.translatable("gui.dndsheets.custom_attacks.delete_all"), b -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new ClearCustomAttacksMessage(entityId));
			this.onClose();
		});
	}

	@Override
	protected Component emptyMessage() {
		return customAttackNames.isEmpty() ? Component.translatable("gui.dndsheets.custom_attacks.empty") : null;
	}
}
