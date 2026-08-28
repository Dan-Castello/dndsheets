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
 * <p>Los dos subcomandos que todo comando de contenido tiene igual: {@code load <archivo>} y
 * {@code list}. Estaban copiados palabra por palabra en {@code dndtraits}, {@code dndpresets},
 * {@code dndspells}, {@code dndmonsters} y {@code dndencounters} — la misma resolución de ruta, el
 * mismo autocompletado, los mismos dos mensajes de error y el mismo {@code catch}.</p>
 *
 * <p>No absorbe a {@code dndoptions}: su {@code load} resuelve antes una categoría (race/background/
 * class), y su {@code list} no lista ids sino los valores de esa categoría. Meterlo aquí a la fuerza
 * pedía dos parámetros que solo usaría él.</p>
 */
final class ContentCommands {
	private ContentCommands() {}

	/** Lo que hace {@code XRegistry::loadFile}. Propia y no {@code Function} porque lanza {@link IOException}. */
	@FunctionalInterface
	interface FileLoader {
		int load(Path file) throws IOException;
	}

	/**
	 * @param plural en minúscula y masculino, como lo lee el jugador: "rasgos", "hechizos", "monstruos".
	 *               Va detrás de "Cargados N", así que un femenino ("opciones") no concuerda — ver arriba
	 *               por qué {@code dndoptions} se queda fuera.
	 */
	static LiteralArgumentBuilder<CommandSourceStack> loadBranch(Path dir, FileLoader loader, String plural) {
		return Commands.literal("load")
			.then(Commands.argument("archivo", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(dir), builder))
				.executes(ctx -> load(ctx, dir, loader, plural)));
	}

	/** @param pluralCapitalizado abre la frase: "Rasgos cargados (3): ...". */
	static LiteralArgumentBuilder<CommandSourceStack> listBranch(Supplier<Set<String>> ids, String pluralCapitalizado) {
		return Commands.literal("list").executes(ctx -> {
			Set<String> loaded = ids.get();
			ctx.getSource().sendSuccess(() -> Component.literal(
				pluralCapitalizado + " cargados (" + loaded.size() + "): " + String.join(", ", loaded)), false);
			return loaded.size();
		});
	}

	private static int load(CommandContext<CommandSourceStack> ctx, Path dir, FileLoader loader, String plural) {
		String fileName = StringArgumentType.getString(ctx, "archivo");
		Path file = dir.resolve(fileName + ".json");

		if (!Files.exists(file)) {
			ctx.getSource().sendFailure(Component.literal("No encontré " + file.toAbsolutePath()));
			return 0;
		}

		try {
			int count = loader.load(file);
			ctx.getSource().sendSuccess(() -> Component.literal("Cargados " + count + " " + plural + " desde " + fileName + ".json"), true);
			return count;
		} catch (IOException | RuntimeException e) {
			//RuntimeException además de IOException: un JSON malformado revienta al parsear, no al leer, y
			//sin esto se lo tragaba el dispatcher de Brigadier y el DM no veía nada.
			ctx.getSource().sendFailure(Component.literal("No pude leer " + fileName + ".json: " + e.getMessage()));
			return 0;
		}
	}
}
