package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>The DM's Rules menu: one row per automation, click to flip Automatic/Manual, plus a row for the
 * numeric values (feet per block, casting time) that a manual table sets by hand. State comes from the
 * server as "KEY=value" pairs (see {@code BrowseActionMessage.sendRules}); each click sends one pair back
 * and the server answers with the fresh list, which reopens this screen.</p>
 */
public class RulesScreen extends ListPickerScreen {
	private final Map<String, Integer> values = new LinkedHashMap<>();

	private RulesScreen(List<String> pairs, Screen parent) {
		super(Component.translatable("gui.dndsheets.rules.title"), parent);
		for (String pair : pairs) {
			String[] kv = pair.split("=", 2);
			values.put(kv[0], Integer.parseInt(kv[1]));
		}
	}

	public static void open(List<String> pairs) {
		Minecraft minecraft = Minecraft.getInstance();
		//Replacing our own previous copy keeps "Back" pointing at the DM Panel instead of stacking screens.
		Screen parent = minecraft.screen instanceof RulesScreen rules ? rules.parent : minecraft.screen;
		minecraft.setScreen(new RulesScreen(pairs, parent));
	}

	@Override
	protected void buildRows() {
		addHeader(Component.translatable("gui.dndsheets.rules.section_auto"));
		for (Map.Entry<String, Integer> e : values.entrySet()) {
			if (e.getKey().equals("FEET") || e.getKey().equals("CAST")) continue;
			boolean auto = e.getValue() != 0;
			addRow(Component.translatable("gui.dndsheets.rules." + e.getKey().toLowerCase(),
					Component.translatable(auto ? "gui.dndsheets.rules.auto" : "gui.dndsheets.rules.manual")),
				b -> send(e.getKey() + "=" + (auto ? 0 : 1)));
		}
		addHeader(Component.translatable("gui.dndsheets.rules.section_tables"));
		for (String table : new String[] {"HIT_DICE", "WEAPON_DAMAGE", "ENCHANT_BONUS"}) {
			addRow(Component.translatable("gui.dndsheets.config." + table.toLowerCase()),
				b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.CONFIG_LIST, table)));
		}
		addHeader(Component.translatable("gui.dndsheets.rules.section_values"));
		addRow(Component.translatable("gui.dndsheets.rules.values", values.get("FEET"), values.get("CAST")),
			b -> Minecraft.getInstance().setScreen(new ValuesForm(values.get("FEET"), values.get("CAST"), this)));
	}

	private static void send(String pair) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.RULES_SET, pair));
	}

	private static class ValuesForm extends SmallFormScreen {
		private final int feet, cast;
		private EditBox feetBox, castBox;

		ValuesForm(int feet, int cast, Screen parent) {
			super(Component.translatable("gui.dndsheets.rules.values_title"), 2, parent);
			this.feet = feet;
			this.cast = cast;
		}

		@Override
		protected void buildForm() {
			feetBox = addField(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.rules.feet"), String.valueOf(feet), 2);
			castBox = addField(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.rules.cast"), String.valueOf(cast), 2);
		}

		@Override
		protected void onConfirm() {
			send("FEET=" + parseIntOr(feetBox.getValue(), feet));
			send("CAST=" + parseIntOr(castBox.getValue(), cast));
		}
	}
}
