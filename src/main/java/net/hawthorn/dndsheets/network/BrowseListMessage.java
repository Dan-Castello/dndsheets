package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.CharacterListScreen;
import net.hawthorn.dndsheets.client.gui.CompendiumEntryScreen;
import net.hawthorn.dndsheets.client.gui.CompendiumListScreen;
import net.hawthorn.dndsheets.client.gui.JournalScreen;
import net.hawthorn.dndsheets.client.gui.FeatScreen;
import net.hawthorn.dndsheets.client.gui.PartyScreen;
import net.hawthorn.dndsheets.client.gui.SubclassScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * <p>Server → client: the response to {@link BrowseActionMessage}, and the one that opens whichever
 * screen applies. Two parallel lists (id and already-formatted label) instead of one object per row: the
 * client does nothing with the data except paint it, so formatting on the server avoids sending half a
 * character sheet over the network just to compose a string.</p>
 */
public class BrowseListMessage {

	//At the end, same as above: the ordinal travels over the network. From GIVE_WEAPON downward are the
	//old List/ListRequest pairs (eight nearly identical classes per side, see invariant 3), merged here in
	//the migration that deleted ~16 classes from this package.
	public enum Kind { MINE, PARTY, CONTENT, DETAIL, JOURNAL, SUBCLASS, FEAT,
		GIVE_WEAPON, GIVE_SPELL, GRANT_TRAIT, PRESET, PRESET_MULTICLASS, SPAWN_MONSTER,
		MANAGE_OPTIONS, CONTENT_ENTRY, CHARACTER_OPTION, ENCOUNTER, ENCOUNTER_DESIGN, RULES, CONFIG, GIVE_MAGIC, CONFIG_SYNC, IDS }

	final Kind kind;
	final List<String> ids;
	final List<Component> labels;
	//What the destination screen needs besides the list: the target player's uuid
	//(GIVE_*/GRANT_TRAIT/PRESET), the category (MANAGE_OPTIONS/CHARACTER_OPTION), or the ContentType's name
	//(CONTENT_ENTRY). Kinds that predate the migration send it empty. Goes at the END of the payload —
	//invariant 2, new fields are never inserted in the middle.
	final String context;

	public BrowseListMessage(Kind kind, List<String> ids, List<Component> labels) {
		this(kind, ids, labels, "");
	}

	public BrowseListMessage(Kind kind, List<String> ids, List<Component> labels, String context) {
		this.kind = kind;
		this.ids = ids;
		this.labels = labels;
		this.context = context;
	}

	public BrowseListMessage(FriendlyByteBuf buffer) {
		this.kind = buffer.readEnum(Kind.class);
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
		this.labels = buffer.readList(FriendlyByteBuf::readComponent);
		this.context = buffer.readUtf();
	}

	public static void buffer(BrowseListMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.kind);
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
		buffer.writeCollection(message.labels, FriendlyByteBuf::writeComponent);
		buffer.writeUtf(message.context);
	}

	/** The send that the eight pairs used to repeat: to the requesting player, with the context their screen needs. */
	public static void send(net.minecraft.server.level.ServerPlayer to, Kind kind, List<String> ids,
							 List<Component> labels, String context) {
		net.hawthorn.dndsheets.DndsheetsMod.PACKET_HANDLER.send(
			net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> to),
			new BrowseListMessage(kind, ids, labels, context));
	}

	public static void handler(BrowseListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> {
			switch (message.kind) {
				case MINE -> CharacterListScreen.open(message.ids, message.labels);
				case PARTY -> PartyScreen.open(message.ids, message.labels);
				case CONTENT -> CompendiumListScreen.open(message.ids, message.labels);
				case JOURNAL -> JournalScreen.open(message.ids, message.labels);
				case SUBCLASS -> SubclassScreen.open(message.ids, message.labels);
				case FEAT -> FeatScreen.open(message.ids, message.labels);
				//A lone entry travels as a one-element list: same message, no new class needed just to
				//carry a long text.
				case DETAIL -> CompendiumEntryScreen.open(
					message.labels.isEmpty() ? "" : message.labels.get(0).getString(),
					message.ids.isEmpty() ? "" : message.ids.get(0));
				case GIVE_WEAPON -> net.hawthorn.dndsheets.client.gui.WeaponGiveListScreen.open(message.context, message.ids);
				case GIVE_SPELL -> net.hawthorn.dndsheets.client.gui.SpellGiveListScreen.open(message.context, message.ids);
				case GRANT_TRAIT -> net.hawthorn.dndsheets.client.gui.TraitGrantScreen.open(message.context, message.ids, plainLabels(message.labels));
				case PRESET -> net.hawthorn.dndsheets.client.gui.PresetScreen.open(message.context, false, message.ids, plainLabels(message.labels));
				case PRESET_MULTICLASS -> net.hawthorn.dndsheets.client.gui.PresetScreen.open("", true, message.ids, plainLabels(message.labels));
				case SPAWN_MONSTER -> net.hawthorn.dndsheets.client.gui.MonsterSpawnListScreen.open(message.ids);
				//The raw JSON array travels as a single label, same trick as DETAIL: the client just
				//forwards it to the screen, which already knew how to parse it.
				case MANAGE_OPTIONS -> net.hawthorn.dndsheets.client.gui.OptionsManageScreen.open(message.context,
					message.labels.isEmpty() ? "[]" : message.labels.get(0).getString());
				case CONTENT_ENTRY -> openContentEntries(message);
				//The active screen is captured so it can be returned to when choosing or canceling — the
				//step list (CharacterSetupScreen) also requests options and shouldn't get kicked out mid-setup.
				case CHARACTER_OPTION -> net.hawthorn.dndsheets.client.gui.CharacterOptionListScreen.open(
					net.minecraft.client.Minecraft.getInstance().screen, message.context, message.ids);
				case ENCOUNTER -> openEncounters(message);
				case IDS -> net.hawthorn.dndsheets.client.gui.ChoiceScreen.deliver(message.context, message.ids, message.labels);
				case CONFIG_SYNC -> net.hawthorn.dndsheets.Config.applyRemote(message.context, message.ids);
				case CONFIG -> net.hawthorn.dndsheets.client.gui.ConfigListScreen.open(message.context, message.ids);
				case GIVE_MAGIC -> {
					List<net.hawthorn.dndsheets.client.gui.CommandListScreen.Row> rows = new ArrayList<>();
					for (int i = 0; i < message.ids.size(); i++) {
						rows.add(new net.hawthorn.dndsheets.client.gui.CommandListScreen.Row(message.labels.get(i),
							"dnditems give " + message.context + " \"" + message.ids.get(i) + "\""));
					}
					net.hawthorn.dndsheets.client.gui.CommandListScreen.open(Component.translatable("gui.dndsheets.dm_panel.give_magic"), rows);
				}
				case RULES -> net.hawthorn.dndsheets.client.gui.RulesScreen.open(message.ids);
				//Bestiary + party budget: the screen keeps the payload and recalculates on its own.
				case ENCOUNTER_DESIGN -> net.hawthorn.dndsheets.client.gui.EncounterDesignerScreen.open(
					message.ids, message.labels, message.context);
			}
		});
	}

	//Each row fires the usual /dndencounters spawn: the permission and the effect still live in the command.
	private static void openEncounters(BrowseListMessage message) {
		List<net.hawthorn.dndsheets.client.gui.CommandListScreen.Row> rows = new java.util.ArrayList<>(message.ids.size());
		for (int i = 0; i < message.ids.size(); i++) {
			Component label = i < message.labels.size() ? message.labels.get(i) : Component.literal(message.ids.get(i));
			rows.add(new net.hawthorn.dndsheets.client.gui.CommandListScreen.Row(label,
				"dndencounters spawn " + message.ids.get(i)));
		}
		net.hawthorn.dndsheets.client.gui.CommandListScreen.open(
			Component.translatable("gui.dndsheets.dm_panel.encounters"), rows);
	}

	private static void openContentEntries(BrowseListMessage message) {
		net.hawthorn.dndsheets.ContentType type;
		try {
			type = net.hawthorn.dndsheets.ContentType.valueOf(message.context);
		} catch (IllegalArgumentException e) {
			return; //A context that isn't a ContentType: corrupt message or version mismatch, discarded.
		}
		net.hawthorn.dndsheets.client.gui.ContentEntryListScreen.open(type,
			message.labels.isEmpty() ? "[]" : message.labels.get(0).getString(),
			message.labels.size() < 2 ? "[]" : message.labels.get(1).getString());
	}

	private static List<String> plainLabels(List<Component> labels) {
		List<String> plain = new java.util.ArrayList<>(labels.size());
		for (Component label : labels) plain.add(label.getString());
		return plain;
	}
}
