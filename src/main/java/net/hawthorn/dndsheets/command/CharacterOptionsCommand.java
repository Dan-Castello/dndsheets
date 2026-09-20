package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * <p>Loads the Race/Background/Class lists that the sheet selector offers (see
 * {@link CharacterOptionsRegistry}) from JSON in {@code <world folder>/dndsheets/races/},
 * {@code /backgrounds/} or {@code /classes/}. Format: a flat array of strings, no objects with
 * an "id" — the value is literally what gets written on the sheet.</p>
 *
 * <pre>["Barbarian", "Bard", "Cleric"]</pre>
 */
@Mod.EventBusSubscriber
public class CharacterOptionsCommand {
	//RACE and BACKGROUND no longer live here (see /dndspecies load / loadbackground in dndsheets_species).
	private static final String[] CATEGORIES = {CharacterOptionsRegistry.CLASS};

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndoptions")
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(Commands.literal("load")
				.then(Commands.argument("category", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CATEGORIES, builder))
					.then(Commands.argument("file", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(dirFor(StringArgumentType.getString(ctx, "category"))), builder))
						.executes(CharacterOptionsCommand::load))))
			.then(Commands.literal("list")
				.then(Commands.argument("category", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CATEGORIES, builder))
					.executes(CharacterOptionsCommand::list))));
	}

	private static Path dirFor(String category) {
		return switch (category) {
			case CharacterOptionsRegistry.CLASS -> DndPaths.CLASSES_DIR;
			default -> null;
		};
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String category = StringArgumentType.getString(ctx, "category");
		Path dir = dirFor(category);
		if (dir == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.options.category_unknown", category));
			return 0;
		}

		String fileName = StringArgumentType.getString(ctx, "file");
		Path file = dir.resolve(fileName + ".json");
		if (!Files.exists(file)) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.content.file_not_found", file.toAbsolutePath().toString()));
			return 0;
		}

		try {
			int count = CharacterOptionsRegistry.loadFile(category, file);
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.options.loaded", count, category, fileName), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.content.read_failed", fileName, e.getMessage()));
			return 0;
		}
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		String category = StringArgumentType.getString(ctx, "category");
		if (!CharacterOptionsRegistry.isValidCategory(category)) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.options.category_unknown", category));
			return 0;
		}
		List<String> values = CharacterOptionsRegistry.get(category);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.options.list", category, values.size(), String.join(", ", values)), false);
		return values.size();
	}
}
