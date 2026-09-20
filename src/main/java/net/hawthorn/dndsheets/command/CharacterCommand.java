package net.hawthorn.dndsheets.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>{@code /dndchar}: multiple characters per player. Until now a sheet was tied to the UUID of whoever
 * used it, with no way to have a second PC, switch characters, or maintain an NPC sheet — see
 * {@link SheetLoader}, where the real change lives.</p>
 *
 * <ul>
 *   <li>{@code /dndchar list} — your characters, with the active one marked. No permissions: it's about
 *   your own stuff.</li>
 *   <li>{@code /dndchar new <name>} — creates one more, without switching to it.</li>
 *   <li>{@code /dndchar switch <id>} — switches to that character (must be yours).</li>
 *   <li>{@code /dndchar npc <name>} — DM only: NPC sheet, no owner, with the same rules as a PC.</li>
 * </ul>
 */
@Mod.EventBusSubscriber
public class CharacterCommand {

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndchar")
			//No requires(hasPermission) at the root: list/new/switch are about one's own characters, there's
			//nothing to gate. Only "npc" and "spawn" require operator, and they require it on their own
			//branch. No subcommand opens the screen, which is what will be wanted 90% of the time; "list"
			//still exists for whoever prefers chat or is reading a script's output.
			.executes(CharacterCommand::openScreen)
			.then(Commands.literal("list")
				.executes(CharacterCommand::list))
			//With no name it opens the wizard, just like /dndchar with no subcommand opens the list. Before,
			//bare "new" was a syntax error ("unknown or incomplete command"), which is the worst possible
			//response: the player who wants to create a character types exactly that, and the mod tells
			//them it doesn't exist. With the name given, it skips the wizard and creates it directly, as
			//before.
			.then(Commands.literal("new")
				.executes(CharacterCommand::openNewCharacter)
				.then(Commands.argument("name", StringArgumentType.greedyString())
					.executes(CharacterCommand::create)))
			//greedyString and not word(): characters are named "Elara the Grey", not "elara2". Requiring a
			//UUID-derived id to switch characters is requiring the player to copy a string that means
			//nothing — the name is accepted, and the id still works because it's what shows up in
			//messages.
			.then(Commands.literal("switch")
				.then(Commands.argument("character", StringArgumentType.greedyString())
					.suggests((ctx, builder) -> suggestCharacters(builder, ownedIds(ctx)))
					.executes(CharacterCommand::switchTo)))
			//No permission: the Ability Score Improvement is chosen by WHOEVER plays the character, not the
			//DM. The server only lets it be spent if one was really still pending (see
			//LevelUpManager.applyImprovement), so opening the screen doesn't grant anything by itself.
			.then(Commands.literal("improve")
				.executes(CharacterCommand::openImprovement))
			//Deleting is about your own stuff, so it doesn't require permission either; permission only
			//comes into play for the DM's NPCs, and SheetLoader.deleteCharacter checks it, not this branch.
			.then(Commands.literal("delete")
				.then(Commands.argument("character", StringArgumentType.greedyString())
					.suggests((ctx, builder) -> suggestCharacters(builder, deletableIds(ctx)))
					.executes(CharacterCommand::delete)))
			.then(Commands.literal("npc")
				.requires(source -> DndsheetsMod.canActAsDm(source))
				.then(Commands.argument("name", StringArgumentType.greedyString())
					.executes(CharacterCommand::createNpc)))
			.then(Commands.literal("spawn")
				.requires(source -> DndsheetsMod.canActAsDm(source))
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(npcIds(), builder))
					.executes(ctx -> spawn(ctx, "minecraft:villager", false))
					//ResourceLocationArgument and NOT StringArgumentType.string(): an entity id has a ":"
					//("minecraft:villager") and Brigadier does NOT allow a colon in an unquoted string. The
					//autocomplete offered "minecraft:villager", typing it failed with "unknown or incomplete
					//command", and the only way to get it right was to quote it by hand — nobody guesses that.
					.then(Commands.argument("entity", ResourceLocationArgument.id())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
							new String[]{"minecraft:villager", "minecraft:zombie", "minecraft:skeleton", "minecraft:armor_stand", "minecraft:iron_golem"}, builder))
						.executes(ctx -> spawn(ctx, ResourceLocationArgument.getId(ctx, "entity").toString(), false))
						//The third argument only makes sense with the second one given: leaving AI on a
						//vanilla villager gets you a villager that wanders off, and on an entity from an NPC
						//mod it's the only thing that makes it useful. See MonsterRegistry.keepsOwnAi.
						.then(Commands.argument("ai", BoolArgumentType.bool())
							.executes(ctx -> spawn(ctx, ResourceLocationArgument.getId(ctx, "entity").toString(),
								BoolArgumentType.getBool(ctx, "ai"))))))));
	}

	//Tab suggestions with one's own ids: without this they'd have to be copied by hand from /dndchar list,
	//and they're a UUID with a suffix — exactly the kind of string nobody types correctly on the first try.
	/**
	 * <p>Autocompletes with the NAMES, and leaves the id as a hint alongside. Suggesting ids would be
	 * autocompleting with the one thing the player doesn't recognize.</p>
	 *
	 * <p>A name with spaces is suggested as-is, without quotes, because the argument is greedyString: what
	 * shows up in the list is exactly what needs to be typed.</p>
	 */
	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestCharacters(
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder, List<String> ids) {
		String written = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
		for (String id : ids) {
			//The label carries the id ONLY if another character has the same name: that way each suggestion
			//is distinct from the others and, above all, resolvable. Suggesting the bare name, two
			//characters with the same name gave two identical options that the command would later reject
			//as ambiguous.
			String label = SheetLoader.suggestionLabelFor(ids, id);
			if (!label.toLowerCase(java.util.Locale.ROOT).startsWith(written)) continue;
			builder.suggest(label, Component.literal(id));
		}
		return builder.buildFuture();
	}

	private static List<String> ownedIds(CommandContext<CommandSourceStack> ctx) {
		try {
			return SheetLoader.charactersOf(ctx.getSource().getPlayerOrException().getStringUUID());
		} catch (CommandSyntaxException e) {
			return List.of();
		}
	}

	//The server already knows which characters it has: it sends the list directly, without the client
	//having to request it first. The round trip is only needed from a GUI button (see BrowseActionMessage).
	private static int openScreen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		net.hawthorn.dndsheets.network.BrowseActionMessage.sendOwnCharacters(ctx.getSource().getPlayerOrException());
		return 1;
	}

	private static int openNewCharacter(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		//The player is resolved OUTSIDE the lambda: getPlayerOrException throws CommandSyntaxException and
		//PacketDistributor.with() doesn't accept a supplier that throws.
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		net.hawthorn.dndsheets.DndsheetsMod.PACKET_HANDLER.send(
			net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
			new net.hawthorn.dndsheets.network.ScreenActionMessage(
				net.hawthorn.dndsheets.network.ScreenActionMessage.Action.NEW_CHARACTER_OPEN));
		return 1;
	}

	private static int openImprovement(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		int pending = net.hawthorn.dndsheets.LevelUpManager.pendingOf(SheetLoader.getServerSheet(player.getStringUUID()));
		if (pending <= 0) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.levelup.none_pending"));
			return 0;
		}
		net.hawthorn.dndsheets.LevelUpManager.openImprovementScreen(player, pending);
		return pending;
	}

	private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String query = StringArgumentType.getString(ctx, "character");
		String characterId = SheetLoader.resolveCharacter(deletableIds(ctx), query);
		if (characterId == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.character.delete_not_found", query));
			return 0;
		}
		//The name is read BEFORE deleting: afterward, the sheet is no longer in memory and the message
		//would show the id.
		String name = SheetLoader.nameOfCharacter(characterId);
		boolean wasNpc = SheetLoader.ownerOf(characterId, SheetLoader.getCharacterSheet(characterId)) == null;
		String error = SheetLoader.deleteCharacter(player, characterId, DndsheetsMod.canActAsDm(ctx.getSource()));
		if (error != null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.character.delete_failed"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.character.deleted_with_path",
			name, characterId + SheetLoader.DELETED_SUFFIX).withStyle(ChatFormatting.GREEN), false);
		//An NPC can have its body placed in the world. Once it's left without a sheet, Combatant.of
		//SILENTLY downgrades it to a vanilla mob: it's still there, it can be hit, and it no longer plays
		//by any rules. Saying so is more honest than letting the DM discover it mid-combat.
		if (wasNpc) {
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.character.delete_body_hint")
				.withStyle(ChatFormatting.GRAY), false);
		}
		return 1;
	}

	//Your own stuff, plus NPCs if you're the DM: exactly what deleteCharacter is going to accept, so as
	//not to suggest an id that gets rejected afterward.
	private static List<String> deletableIds(CommandContext<CommandSourceStack> ctx) {
		List<String> ids = new ArrayList<>();
		try {
			ids.addAll(SheetLoader.charactersOf(ctx.getSource().getPlayerOrException().getStringUUID()));
		} catch (CommandSyntaxException ignored) {
			//Console: has no characters of its own, can only touch NPCs.
		}
		if (DndsheetsMod.canActAsDm(ctx.getSource())) ids.addAll(SheetLoader.npcIds());
		return ids;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String activeId = SheetLoader.activeCharacterOf(player.getStringUUID());
		List<String> owned = SheetLoader.charactersOf(player.getStringUUID());

		if (owned.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.character.none_yet").withStyle(ChatFormatting.GRAY), false);
			return 0;
		}

		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.character.list_header").withStyle(ChatFormatting.GOLD), false);
		for (String characterId : owned) {
			JsonObject sheet = SheetLoader.getCharacterSheet(characterId);
			String name = sheet != null && sheet.has("characterName") ? sheet.get("characterName").getAsString() : "(unnamed)";
			boolean isActive = characterId.equals(activeId);
			//Same text the autocomplete suggests, so what's read here is literally what needs to be typed.
			//The id only shows up when two characters share a name, which is when it matters.
			String label = SheetLoader.suggestionLabelFor(owned, characterId);
			String suffix = label.equals(name) ? "  [" + characterId + "]" : "";
			ctx.getSource().sendSuccess(() -> Component.literal((isActive ? " ▶ " : "   ") + label + suffix)
				.withStyle(isActive ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		}
		return owned.size();
	}

	private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name");
		String characterId = SheetLoader.createCharacter(player.getStringUUID(), name);
		//The exact command to switch to it is given: creating without activating is deliberate, but
		//without this line it would look like the command did nothing.
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.character.created_switch_hint", name, characterId)
			.withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int switchTo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String query = StringArgumentType.getString(ctx, "character");
		String characterId = SheetLoader.resolveCharacter(SheetLoader.charactersOf(player.getStringUUID()), query);
		if (characterId == null || !SheetLoader.switchCharacter(player, characterId)) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.character.switch_not_found", query));
			return 0;
		}
		JsonObject sheet = SheetLoader.getCharacterSheet(characterId);
		String name = sheet != null && sheet.has("characterName") ? sheet.get("characterName").getAsString() : characterId;
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.character.switched", name).withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	//Only ownerless sheets: giving a body to the PC of someone who's playing it makes no sense, you'd have
	//two.
	private static List<String> npcIds() {
		return SheetLoader.npcIds();
	}

	/**
	 * <p>Gives a body to an NPC sheet at the point the DM is looking at. The base entity is configurable
	 * because a tavern keeper and a guard captain shouldn't look the same; a villager by default, which is
	 * the closest thing to "a person".</p>
	 */
	private static int spawn(CommandContext<CommandSourceStack> ctx, String baseEntityId, boolean keepsOwnAi) throws CommandSyntaxException {
		String characterId = StringArgumentType.getString(ctx, "id");
		net.minecraft.world.phys.Vec3 pos = ctx.getSource().getPosition();
		net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();

		net.minecraft.world.entity.Entity spawned = SheetLoader.spawnNpc(level, pos.x, pos.y, pos.z, characterId, baseEntityId, keepsOwnAi);
		if (spawned == null) {
			//The two possible reasons are distinguished, instead of a "couldn't do it" that forces guessing
			//which one it was.
			ctx.getSource().sendFailure(
				SheetLoader.getCharacterSheet(characterId) == null
					? Component.translatable("chat.dndsheets.character.npc_sheet_missing", characterId)
					: Component.translatable("chat.dndsheets.character.invalid_entity", baseEntityId));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.character.npc_spawned", spawned.getName().getString()).withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int createNpc(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		String characterId = SheetLoader.createNpc(name);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.character.npc_created", name, characterId)
			.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}
}
