package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.Condition;
import net.hawthorn.dndsheets.CreatureType;
import net.hawthorn.dndsheets.DamageTypes;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <p>The mod's "pick instead of typing" list. One screen, three ways to answer: {@link Mode#SINGLE} (click a
 * row, done), {@link Mode#MULTI} (toggle rows, then Done) and {@link Mode#STATES} (each row cycles through
 * states — off / resistant / vulnerable / immune — for things that carry a value). It answers with the same
 * comma-separated text the forms used to ask you to type, so nothing downstream changes.</p>
 *
 * <p>{@link #request} is the single entry point: it knows which sources the client can list by itself (items,
 * enchantments, conditions, damage and creature types) and which only the server knows (spells, traits,
 * monsters, weapons, magic items, classes) — for those it asks, and {@link #deliver} opens the screen when the
 * answer arrives.</p>
 */
public class ChoiceScreen extends ListPickerScreen {
	public enum Mode { SINGLE, MULTI, STATES }

	public record Option(String id, Component label) {}

	private static final String[] ON_OFF = {"", "on"};
	private static final String[] AFFINITY_STATES = {"", "resistant", "vulnerable", "immune"};

	private final List<Option> options;
	private final Mode mode;
	private final String[] states;
	private final Map<String, Integer> state = new LinkedHashMap<>();
	//The token the field already had for an id ("dndsheets:goblin x4"): kept if the id stays selected.
	private final Map<String, String> tokens = new LinkedHashMap<>();
	private final Consumer<String> onDone;

	private ChoiceScreen(Component title, List<Option> options, Mode mode, String currentCsv, Consumer<String> onDone, Screen parent) {
		super(title, parent);
		this.options = options;
		this.mode = mode;
		this.states = mode == Mode.STATES ? AFFINITY_STATES : ON_OFF;
		this.onDone = onDone;
		for (String raw : currentCsv.split(",")) {
			String token = raw.trim();
			if (token.isEmpty()) continue;
			String id = mode == Mode.STATES ? token.split(":")[0].trim() : token.split("\\s+")[0];
			tokens.put(id, token);
			int index = 1;
			if (mode == Mode.STATES && token.contains(":")) {
				index = Math.max(1, java.util.Arrays.asList(states).indexOf(token.substring(token.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT)));
			}
			state.put(id, index);
		}
	}

	private record Pending(String source, Mode mode, String current, Consumer<String> onDone) {}

	private static Pending pending;

	/** Opens the right picker for {@code source}; {@code onDone} gets the comma-separated result. */
	public static void request(String source, boolean multi, String current, Consumer<String> onDone) {
		Mode mode = source.equals("DAMAGE_AFFINITY") ? Mode.STATES : multi ? Mode.MULTI : Mode.SINGLE;
		switch (source) {
			case "ITEM" -> ItemPickerScreen.open(item -> true, current, multi, onDone);
			case "ENCHANTMENT" -> {
				List<Option> list = new ArrayList<>();
				ForgeRegistries.ENCHANTMENTS.getEntries().forEach(e ->
					list.add(new Option(e.getKey().location().toString(), Component.translatable(e.getValue().getDescriptionId()))));
				open(source, list, mode, current, onDone);
			}
			case "CONDITION" -> open(source, conditions(), mode, current, onDone);
			case "EFFECT" -> {
				List<Option> list = new ArrayList<>();
				for (String name : new String[] {"poison", "burning", "bleeding"}) list.add(new Option(name, Component.literal(name)));
				list.addAll(conditions());
				open(source, list, mode, current, onDone);
			}
			case "CREATURE_TYPE" -> {
				List<Option> list = new ArrayList<>();
				for (CreatureType type : CreatureType.values()) {
					if (type != CreatureType.UNKNOWN) list.add(new Option(type.name().toLowerCase(Locale.ROOT), Component.literal(type.name().toLowerCase(Locale.ROOT))));
				}
				open(source, list, mode, current, onDone);
			}
			case "DAMAGE_AFFINITY" -> {
				List<Option> list = new ArrayList<>();
				for (String type : DamageTypes.CANONICAL) list.add(new Option(type, Component.literal(type)));
				open(source, list, mode, current, onDone);
			}
			default -> {
				//Only the server has these registries (SPELL, TRAIT, MONSTER, WEAPON, MAGIC_ITEM, CLASS).
				pending = new Pending(source, mode, current, onDone);
				DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.LIST_IDS, source));
			}
		}
	}

	private static List<Option> conditions() {
		List<Option> list = new ArrayList<>();
		for (Condition condition : Condition.values()) list.add(new Option(condition.label(), Component.literal(condition.label())));
		return list;
	}

	/** The server's answer to a {@code LIST_IDS} request. */
	public static void deliver(String source, List<String> ids, List<Component> labels) {
		if (pending == null || !pending.source().equals(source)) return;
		List<Option> list = new ArrayList<>();
		for (int i = 0; i < ids.size(); i++) list.add(new Option(ids.get(i), labels.get(i)));
		Pending p = pending;
		pending = null;
		open(source, list, p.mode(), p.current(), p.onDone());
	}

	private static void open(String source, List<Option> options, Mode mode, String current, Consumer<String> onDone) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new ChoiceScreen(Component.translatable("gui.dndsheets.choice.title"), options, mode, current, onDone, minecraft.screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	private void finish(String csv) {
		Minecraft.getInstance().setScreen(parent);
		onDone.accept(csv);
	}

	private String result() {
		List<String> out = new ArrayList<>();
		for (Option option : options) {
			int s = state.getOrDefault(option.id(), 0);
			if (s == 0) continue;
			if (mode == Mode.STATES) out.add(option.id() + ":" + states[s]);
			else out.add(tokens.getOrDefault(option.id(), option.id()));
		}
		//Whatever the field held that this list doesn't offer (a hand-typed id, an id from a pack not loaded) is kept.
		for (Map.Entry<String, String> extra : tokens.entrySet()) {
			boolean known = options.stream().anyMatch(o -> o.id().equals(extra.getKey()));
			if (!known) out.add(extra.getValue());
		}
		return String.join(", ", out);
	}

	@Override
	protected void buildRows() {
		if (mode != Mode.SINGLE) addRow(Component.translatable("gui.dndsheets.choice.done"), b -> finish(result()));
		for (Option option : options) {
			addRow(rowLabel(option), b -> {
				if (mode == Mode.SINGLE) {
					finish(option.id());
					return;
				}
				state.put(option.id(), (state.getOrDefault(option.id(), 0) + 1) % states.length);
				this.rebuildWidgets();
			});
		}
	}

	private Component rowLabel(Option option) {
		int s = state.getOrDefault(option.id(), 0);
		if (mode == Mode.SINGLE) return option.label();
		String mark = mode == Mode.STATES ? (s == 0 ? "" : " — " + states[s]) : "";
		return Component.literal(mode == Mode.MULTI ? (s == 0 ? "[ ] " : "[x] ") : "").append(option.label()).append(mark);
	}
}
