package net.hawthorn.dndsheets.command;

import net.hawthorn.dndsheets.ContentNames;

import com.mojang.brigadier.context.CommandContext;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.EncounterRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;
import java.util.Set;

/**
 * <p>{@code /dndencounters}: groups of monsters prepared before the session and dropped in all at once.</p>
 *
 * <p>The file format is the usual one — an array of objects with {@code id} in
 * {@code <world>/dndsheets/encounters/}, loaded only at startup — and the composition is written as text:</p>
 *
 * <pre>
 * {
 *   "id": "goblin_ambush",
 *   "name": "Goblin ambush",
 *   "monsters": ["dndsheets:goblin x4", "dndsheets:wolf x2"]
 * }
 * </pre>
 *
 * <p>Without {@code load} nothing needs to be touched: the whole folder is read at server startup, same
 * as the rest of the content. {@code load} exists to hot-reload what you just edited.</p>
 */
@Mod.EventBusSubscriber
public class EncounterCommand {
	private static final Path ENCOUNTERS_DIR = DndPaths.ENCOUNTERS_DIR;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndencounters")
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(ContentCommands.loadBranch(ENCOUNTERS_DIR, EncounterRegistry::loadFile, "encounters"))
			.then(Commands.literal("list").executes(EncounterCommand::list))
			.then(Commands.literal("spawn")
				.then(Commands.argument("encounterId", ResourceLocationArgument.id())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(EncounterRegistry.ids(), builder))
					.executes(ctx -> spawn(ctx, ctx.getSource().getPosition()))
					//With an explicit position: prepare the ambush on the other side of the door without
					//having to go there, which is exactly when a DM wants a saved encounter.
					.then(Commands.argument("position", Vec3Argument.vec3())
						.executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, "position")))))));
	}


	private static int list(CommandContext<CommandSourceStack> ctx) {
		Set<String> ids = EncounterRegistry.ids();
		if (ids.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.encounter.none",
				ENCOUNTERS_DIR.toAbsolutePath().toString()), false);
			return 0;
		}
		for (String id : ids) {
			EncounterRegistry.Encounter encounter = EncounterRegistry.get(id);
			ctx.getSource().sendSuccess(() -> Component.literal(id + " — ").append(ContentNames.of(encounter.name()))
				.append(": " + EncounterRegistry.describe(encounter)), false);
		}
		return ids.size();
	}

	private static int spawn(CommandContext<CommandSourceStack> ctx, Vec3 where) {
		String id = ResourceLocationArgument.getId(ctx, "encounterId").toString();
		EncounterRegistry.Encounter encounter = EncounterRegistry.get(id);
		if (encounter == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.encounter.no_such", id));
			return 0;
		}

		ServerLevel level = ctx.getSource().getLevel();
		int spawned = EncounterRegistry.spawn(level, where, encounter);
		int total = encounter.total();

		if (spawned == 0) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.encounter.nothing_spawned", ContentNames.of(encounter.name())));
			return 0;
		}

		//How many are missing is stated, not just how many spawned: an encounter missing its boss because
		//its id is misspelled still plays out, and nobody notices until later.
		Component missing = spawned < total ? Component.translatable("chat.dndsheets.encounter.missing", total - spawned) : Component.empty();
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.encounter.spawned", ContentNames.of(encounter.name()), spawned, missing), true);
		return spawned;
	}
}
