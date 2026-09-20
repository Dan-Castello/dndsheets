package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.hawthorn.dndsheets.DndPaths;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Supplier;

/**
 * <p>The two subcommands every content command has in common: {@code load <file>} and
 * {@code list}. They were copied word for word into {@code dndtraits}, {@code dndpresets},
 * {@code dndspells}, {@code dndmonsters} and {@code dndencounters} — the same path resolution, the
 * same autocomplete, the same two error messages and the same {@code catch}.</p>
 *
 * <p>It doesn't absorb {@code dndoptions}: its {@code load} resolves a category first (race/background/
 * class), and its {@code list} doesn't list ids but the values of that category. Forcing it in here
 * would require two parameters that only it would use.</p>
 */
final class ContentCommands {
	private ContentCommands() {}

	/** What {@code XRegistry::loadFile} does. Its own interface and not {@code Function} because it throws {@link IOException}. */
	@FunctionalInterface
	interface FileLoader {
		int load(Path file) throws IOException;
	}

	/**
	 * @param plural lowercase, as the player reads it: "traits", "spells", "monsters". Follows
	 *               "Loaded N", so make sure it reads naturally in that message — see above for why
	 *               {@code dndoptions} is left out.
	 */
	static LiteralArgumentBuilder<CommandSourceStack> loadBranch(Path dir, FileLoader loader, String plural) {
		return Commands.literal("load")
			.then(Commands.argument("file", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(dir), builder))
				.executes(ctx -> load(ctx, dir, loader, plural)));
	}

	/** @param pluralCapitalized opens the sentence: "Traits loaded (3): ...". */
	static LiteralArgumentBuilder<CommandSourceStack> listBranch(Supplier<Set<String>> ids, String pluralCapitalized) {
		return Commands.literal("list").executes(ctx -> {
			Set<String> loaded = ids.get();
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.content.list_loaded",
				pluralCapitalized, loaded.size(), String.join(", ", loaded)), false);
			return loaded.size();
		});
	}

	private static int load(CommandContext<CommandSourceStack> ctx, Path dir, FileLoader loader, String plural) {
		String fileName = StringArgumentType.getString(ctx, "file");
		Path file = dir.resolve(fileName + ".json");

		if (!Files.exists(file)) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.content.file_not_found", file.toAbsolutePath().toString()));
			return 0;
		}

		try {
			int count = loader.load(file);
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.content.loaded_from_file", count, plural, fileName), true);
			return count;
		} catch (IOException | RuntimeException e) {
			//RuntimeException in addition to IOException: a malformed JSON blows up while parsing, not
			//while reading, and without this Brigadier's dispatcher would swallow it and the DM would see
			//nothing.
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.content.read_failed", fileName, e.getMessage()));
			return 0;
		}
	}
}
