package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>A compendium entry's sheet: the text the server composed, as-is. The client doesn't decide which
 * fields to show or how — that lives in {@code CompendiumQuery}, together with the registries that know
 * the shape of each content type.</p>
 *
 * <p>The first line is treated as the title and the rest as the body, because that's how the server
 * writes them. Long lines are wrapped to the dialog's width instead of running off the edge.</p>
 */
public class CompendiumEntryScreen extends ModalDialogScreen {

	private static final int WIDTH = 300;
	private static final int HEIGHT = 200;
	private static final int PADDING = 12;

	private final String text;
	private final String entryId; //"category|id" as sent by the server, verbatim; empty if it wasn't sent.
	private final Screen parent;
	private List<net.minecraft.util.FormattedCharSequence> lines = List.of();
	private String heading = "";

	private CompendiumEntryScreen(String text, String entryId, Screen parent) {
		super(Component.translatable("gui.dndsheets.compendium.title"), WIDTH, HEIGHT);
		this.text = text;
		this.entryId = entryId;
		this.parent = parent;
	}

	public static void open(String text, String entryId) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new CompendiumEntryScreen(text, entryId, minecraft.screen));
	}

	/**
	 * <p>What can be DONE with the entry being viewed, beyond just reading it. The compendium used to be
	 * lookup-only: to summon the monster you'd just searched for, you had to close it and type the command
	 * with the id by hand.</p>
	 *
	 * <p>Each action sends the command that ALREADY exists, just like {@code CommandListScreen} does (see
	 * PROJECT_CONTEXT, "cheap GUI path for any existing command"): no new network message and, more
	 * importantly, <b>no second permission check to maintain</b> — the gate is still exactly where it was,
	 * in the command itself, and it's the one that will answer anyone who isn't allowed.</p>
	 *
	 * <p>The default target is {@code @s}, but not the only one: the button next to it opens the SAME
	 * player selector as the DM Panel and repeats this command with the chosen name. Before, the
	 * compendium entry only knew how to give it to whoever was looking at it, so a DM searching for a
	 * weapon for a player had to close the compendium and start over from the DM Panel. The commands accept
	 * any player ({@code EntityArgument.players()}), so nothing more is needed than swapping the target.</p>
	 *
	 * @return null if the category has no action, or if the id wasn't provided.
	 */
	private String actionCommand(String target) {
		String[] parts = entryId.split("[|]", 2);
		if (parts.length != 2 || parts[1].isBlank()) return null;
		String id = parts[1];
		return switch (parts[0].toLowerCase(java.util.Locale.ROOT)) {
			case "spells" -> "dndspells learn " + target + " " + id;
			case "monsters" -> "dndmonsters spawn " + id;
			case "weapons" -> "dndweapons give " + target + " " + id;
			case "traits" -> "dndtraits grant " + target + " " + id;
			//Quoted, unlike the others: this is the only one that reads its id with StringArgumentType.string()
			//instead of ResourceLocationArgument, and Brigadier does NOT accept ":" in an unquoted string — a
			//bare "dnditems give @s dndsheets:mace_of_terror" gets cut off at the colon and fails.
			case "items" -> "dnditems give " + target + " \"" + id + "\"";
			default -> null;
		};
	}

	/** Summoning a monster has no "for whom": it appears where whoever requested it is standing, and that's the end of it. */
	private boolean hasTarget() {
		return !"monsters".equals(entryId.split("[|]", 2)[0].toLowerCase(java.util.Locale.ROOT));
	}

	private Component actionLabel() {
		return switch (entryId.split("[|]", 2)[0].toLowerCase(java.util.Locale.ROOT)) {
			case "spells" -> Component.translatable("gui.dndsheets.compendium.action.learn");
			case "monsters" -> Component.translatable("gui.dndsheets.compendium.action.summon");
			case "traits" -> Component.translatable("gui.dndsheets.compendium.action.grant");
			default -> Component.translatable("gui.dndsheets.compendium.action.give");
		};
	}

	@Override
	protected void init() {
		String[] parts = text.split("\n", 2);
		heading = parts[0];

		//Line wrapping happens in init(), not in render(): render runs 60 times per second, and re-wrapping
		//the text every frame would be repeated work for a result that never changes.
		lines = new ArrayList<>();
		if (parts.length > 1) {
			for (String paragraph : parts[1].split("\n")) {
				if (paragraph.isBlank()) {
					lines.add(Component.empty().getVisualOrderText());
					continue;
				}
				lines.addAll(this.font.split(Component.literal(paragraph), WIDTH - PADDING * 2));
			}
		}

		addModalButton(WIDTH - 60 - PADDING, HEIGHT - 24, 60, 16, Component.translatable("gui.dndsheets.common.close"), b -> this.onClose());

		String command = actionCommand("@s");
		if (command != null) {
			//To the left of Close, in the same row. Clicking it closes the entry: the action has its own
			//response in chat, and staying on the description screen would hide exactly what just happened.
			addModalButton(PADDING, HEIGHT - 24, 96, 16, actionLabel(), b -> {
				Minecraft.getInstance().player.connection.sendCommand(command);
				this.onClose();
			});
		}

		//The second possible target: another player. DM-only, because the command behind it already
		//requires operator, and offering a regular player a button that will always tell them they can't
		//is worse than not offering it at all. The real check still lives in the command, not here.
		if (command != null && hasTarget() && Minecraft.getInstance().player.hasPermissions(2)) {
			addModalButton(PADDING + 100, HEIGHT - 24, 108, 16, Component.translatable("gui.dndsheets.compendium.action.to_player"),
				b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.compendium.pick_player"), uuid -> {
					String name = playerNameOf(uuid);
					if (name != null) Minecraft.getInstance().player.connection.sendCommand(actionCommand(name));
					//Back to the world, not back to the entry: same as the button above, the response is in chat.
					Minecraft.getInstance().setScreen(null);
				}));
		}
	}

	/** The picker returns a UUID; the commands want a player name. The client already has the list of connected players. */
	private static String playerNameOf(String uuid) {
		net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) return null;
		net.minecraft.client.multiplayer.PlayerInfo info;
		try {
			info = connection.getPlayerInfo(java.util.UUID.fromString(uuid));
		} catch (IllegalArgumentException e) {
			return null;
		}
		return info == null ? null : info.getProfile().getName();
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		renderPanel(guiGraphics);
		int x = dialogLeft() + PADDING;
		int y = dialogTop() + PADDING;

		guiGraphics.drawString(this.font, heading, x, y, GuiStyle.TITLE_COLOR);
		y += 14;

		for (net.minecraft.util.FormattedCharSequence line : lines) {
			//Cut off once it reaches the close button instead of writing over it: a long entry gets
			//truncated, which is preferable to illegible text on top of a control.
			if (y > dialogTop() + HEIGHT - 34) break;
			guiGraphics.drawString(this.font, line, x, y, GuiStyle.MUTED_COLOR);
			y += 10;
		}

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
