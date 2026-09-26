package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>In-game editor for the toml's three lists (hit dice per class, default weapon damage, enchantment
 * bonus). One row per entry; a row opens its form, "+ Add" opens an empty one. Every change goes to the
 * server ({@code CONFIG_SET}), which validates it with the toml's own validators, writes the file, applies
 * it and answers with the fresh list, which reopens this screen.</p>
 */
public class ConfigListScreen extends ListPickerScreen {
	private final String table;
	private final List<String> entries;

	private ConfigListScreen(String table, List<String> entries, Screen parent) {
		super(Component.translatable("gui.dndsheets.config." + table.toLowerCase()), parent);
		this.table = table;
		this.entries = entries;
	}

	public static void open(String table, List<String> entries) {
		Minecraft minecraft = Minecraft.getInstance();
		//An echo after a save replaces the previous copy, keeping "Back" pointing where it did.
		Screen parent = minecraft.screen instanceof ConfigListScreen list ? list.parent : minecraft.screen;
		minecraft.setScreen(new ConfigListScreen(table, entries, parent));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String entry : entries) addRow(Component.literal(entry), b -> Minecraft.getInstance().setScreen(new EntryForm(table, entry, this)));
		addRow(Component.translatable("gui.dndsheets.config.add"), b -> Minecraft.getInstance().setScreen(new EntryForm(table, "", this)));
	}

	private static void send(String table, String oldEntry, String newEntry) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.CONFIG_SET,
			table + "\u0001" + oldEntry + "\u0001" + newEntry));
	}

	private static class EntryForm extends SmallFormScreen {
		private static final String[] ABILITIES = {"str", "dex"};

		private final String table;
		private final String oldEntry;
		private final String[] parts;
		private EditBox first, second;
		private CycleField ability;

		EntryForm(String table, String oldEntry, Screen parent) {
			super(Component.translatable("gui.dndsheets.config.entry_title"), 2, parent);
			this.table = table;
			this.oldEntry = oldEntry;
			this.parts = oldEntry.split(table.equals("HIT_DICE") ? ":" : ";", -1);
		}

		private String part(int i, String fallback) {
			return i < parts.length && !parts[i].isBlank() ? parts[i] : fallback;
		}

		@Override
		protected void buildForm() {
			String prefix = "gui.dndsheets.config.field." + table.toLowerCase();
			if (table.equals("WEAPON_DAMAGE")) {
				first = addPickField(I18n.get(prefix + ".item"), part(0, "minecraft:stick"), 64, "ITEM", false);
				second = addField(I18n.get(prefix + ".die"), part(1, "1d6"), 8);
				int start = part(2, "str").equalsIgnoreCase("dex") ? 1 : 0;
				ability = addCycleButton(I18n.get(prefix + ".ability"), ABILITIES, ABILITIES, start);
			} else {
				String source = table.equals("HIT_DICE") ? "CLASS" : "ENCHANTMENT";
				first = addPickField(I18n.get(prefix + ".key"), part(0, ""), 64, source, false);
				second = addField(I18n.get(prefix + ".value"), part(1, table.equals("HIT_DICE") ? "8" : "1"), 8);
			}
		}

		private String composed() {
			String a = first.getValue().trim(), b = second.getValue().trim();
			if (a.isEmpty() || b.isEmpty()) return "";
			return switch (table) {
				case "HIT_DICE" -> a + ":" + b;
				case "WEAPON_DAMAGE" -> a + ";" + b + ";" + ability.value();
				default -> a + ";" + b;
			};
		}

		@Override
		protected void onConfirm() {
			String entry = composed();
			if (!entry.isEmpty()) send(table, oldEntry, entry);
		}

		@Override
		protected boolean showDeleteButton() {
			return !oldEntry.isEmpty();
		}

		@Override
		protected void onDelete() {
			send(table, oldEntry, "");
		}
	}
}
