package net.hawthorn.dndsheets.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.DndPaths;
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
import java.util.ArrayList;
import java.util.List;

/**
 * <p>Carga las listas de Raza/Trasfondo/Clase que ofrece el selector de la hoja (ver
 * {@link CharacterOptionsRegistry}) desde JSON en {@code <carpeta del mundo>/dndsheets/races/},
 * {@code /backgrounds/} o {@code /classes/}. Formato: un array plano de strings, nada de objetos con
 * "id" — el valor es literalmente lo que se escribe en la hoja.</p>
 *
 * <pre>["Bárbaro", "Bardo", "Clérigo"]</pre>
 */
@Mod.EventBusSubscriber
public class CharacterOptionsCommand {
	private static final String[] CATEGORIES = {CharacterOptionsRegistry.RACE, CharacterOptionsRegistry.BACKGROUND, CharacterOptionsRegistry.CLASS};

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndoptions")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("load")
				.then(Commands.argument("categoria", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CATEGORIES, builder))
					.then(Commands.argument("archivo", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(dirFor(StringArgumentType.getString(ctx, "categoria"))), builder))
						.executes(CharacterOptionsCommand::load))))
			.then(Commands.literal("list")
				.then(Commands.argument("categoria", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CATEGORIES, builder))
					.executes(CharacterOptionsCommand::list))));
	}

	private static Path dirFor(String category) {
		return switch (category) {
			case CharacterOptionsRegistry.RACE -> DndPaths.RACES_DIR;
			case CharacterOptionsRegistry.BACKGROUND -> DndPaths.BACKGROUNDS_DIR;
			case CharacterOptionsRegistry.CLASS -> DndPaths.CLASSES_DIR;
			default -> null;
		};
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String category = StringArgumentType.getString(ctx, "categoria");
		Path dir = dirFor(category);
		if (dir == null) {
			ctx.getSource().sendFailure(Component.literal("Categoría \"" + category + "\" no reconocida. Usa: race, background o class."));
			return 0;
		}

		String fileName = StringArgumentType.getString(ctx, "archivo");
		Path file = dir.resolve(fileName + ".json");
		if (!Files.exists(file)) {
			ctx.getSource().sendFailure(Component.literal("No encontré " + file.toAbsolutePath()));
			return 0;
		}

		try {
			int count = loadFile(category, file);
			ctx.getSource().sendSuccess(() -> Component.literal("Cargadas " + count + " opciones de " + category + " desde " + fileName + ".json"), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.literal("No pude leer " + fileName + ".json: " + e.getMessage()));
			return 0;
		}
	}

	//Público: también lo usa DndPaths para precargar solo todos los .json de la carpeta al arrancar el servidor.
	public static int loadFile(String category, Path file) throws IOException {
		String json = Files.readString(file);
		JsonArray array = JsonParser.parseString(json).getAsJsonArray();
		List<String> values = new ArrayList<>();
		for (var element : array) values.add(element.getAsString());
		CharacterOptionsRegistry.replace(category, values);
		return values.size();
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		String category = StringArgumentType.getString(ctx, "categoria");
		if (!CharacterOptionsRegistry.isValidCategory(category)) {
			ctx.getSource().sendFailure(Component.literal("Categoría \"" + category + "\" no reconocida. Usa: race, background o class."));
			return 0;
		}
		List<String> values = CharacterOptionsRegistry.get(category);
		ctx.getSource().sendSuccess(() -> Component.literal("Opciones de " + category + " (" + values.size() + "): " + String.join(", ", values)), false);
		return values.size();
	}
}
