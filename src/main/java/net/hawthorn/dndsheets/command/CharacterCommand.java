package net.hawthorn.dndsheets.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>{@code /dndchar}: varios personajes por jugador. Hasta ahora una hoja estaba atada al UUID de quien
 * la usaba y no había forma de tener un segundo PJ, cambiar de personaje ni llevar una ficha de PNJ — ver
 * {@link SheetLoader}, donde vive el cambio de verdad.</p>
 *
 * <ul>
 *   <li>{@code /dndchar list} — tus personajes, con el activo marcado. Sin permisos: es sobre lo tuyo.</li>
 *   <li>{@code /dndchar new <nombre>} — crea uno más, sin ponértelo.</li>
 *   <li>{@code /dndchar switch <id>} — te pones ese personaje (tiene que ser tuyo).</li>
 *   <li>{@code /dndchar npc <nombre>} — solo DM: ficha de PNJ, sin dueño, con las mismas reglas que un PJ.</li>
 * </ul>
 */
@Mod.EventBusSubscriber
public class CharacterCommand {

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndchar")
			//Sin requires(hasPermission) en la raíz: list/new/switch son sobre los personajes de uno mismo,
			//no hay nada que gatear. Solo "npc" y "spawn" piden operador, y lo piden en su propia rama.
			//Sin subcomando abre la pantalla, que es lo que va a querer el 90% de las veces; "list" sigue
			//existiendo para quien prefiera el chat o esté leyendo la salida de un script.
			.executes(CharacterCommand::openScreen)
			.then(Commands.literal("list")
				.executes(CharacterCommand::list))
			.then(Commands.literal("new")
				.then(Commands.argument("nombre", StringArgumentType.greedyString())
					.executes(CharacterCommand::create)))
			//greedyString y no word(): los personajes se llaman "Elara la Gris", no "elara2". Pedir un id
			//derivado del UUID para cambiar de personaje es pedir que se copie una cadena que no significa
			//nada — se acepta el nombre, y el id sigue valiendo porque es lo que sale en los mensajes.
			.then(Commands.literal("switch")
				.then(Commands.argument("personaje", StringArgumentType.greedyString())
					.suggests((ctx, builder) -> suggestCharacters(builder, ownedIds(ctx)))
					.executes(CharacterCommand::switchTo)))
			//Sin permiso: la Mejora de Característica la elige QUIEN lleva el personaje, no el DM. El servidor
			//solo la deja gastar si de verdad quedaba alguna pendiente (ver LevelUpManager.applyImprovement),
			//así que abrir la pantalla no concede nada por sí solo.
			.then(Commands.literal("mejora")
				.executes(CharacterCommand::openImprovement))
			//Borrar es sobre lo tuyo, así que tampoco pide permiso; el permiso solo entra para los PNJ del
			//DM, y lo comprueba SheetLoader.deleteCharacter, no esta rama.
			.then(Commands.literal("delete")
				.then(Commands.argument("personaje", StringArgumentType.greedyString())
					.suggests((ctx, builder) -> suggestCharacters(builder, deletableIds(ctx)))
					.executes(CharacterCommand::delete)))
			.then(Commands.literal("npc")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("nombre", StringArgumentType.greedyString())
					.executes(CharacterCommand::createNpc)))
			.then(Commands.literal("spawn")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(npcIds(), builder))
					.executes(ctx -> spawn(ctx, "minecraft:villager"))
					.then(Commands.argument("entidad", StringArgumentType.string())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
							new String[]{"minecraft:villager", "minecraft:zombie", "minecraft:skeleton", "minecraft:armor_stand", "minecraft:iron_golem"}, builder))
						.executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "entidad")))))));
	}

	//Sugerencias de tab con los ids propios: sin esto habría que copiarlos a mano del /dndchar list, y son
	//UUID con un sufijo — exactamente el tipo de cadena que nadie teclea bien a la primera.
	/**
	 * <p>Autocompleta con los NOMBRES, y deja el id como pista al lado. Sugerir ids sería autocompletar con
	 * lo único que el jugador no reconoce.</p>
	 *
	 * <p>Un nombre con espacios se sugiere tal cual, sin comillas, porque el argumento es greedyString: lo
	 * que se ve en la lista es exactamente lo que hay que escribir.</p>
	 */
	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestCharacters(
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder, List<String> ids) {
		String written = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
		for (String id : ids) {
			//El rótulo lleva el id SOLO si otro personaje se llama igual: así cada sugerencia es distinta de
			//las demás y, sobre todo, resoluble. Sugiriendo el nombre a secas, dos personajes llamados igual
			//daban dos opciones idénticas que el comando rechazaba después por ambiguas.
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

	//El servidor ya sabe qué personajes tiene: manda la lista directamente, sin que el cliente tenga que
	//pedirla primero. La ida y vuelta solo hace falta desde un botón de GUI (ver BrowseActionMessage).
	private static int openScreen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		net.hawthorn.dndsheets.network.BrowseActionMessage.sendOwnCharacters(ctx.getSource().getPlayerOrException());
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
		String query = StringArgumentType.getString(ctx, "personaje");
		String characterId = SheetLoader.resolveCharacter(deletableIds(ctx), query);
		if (characterId == null) {
			ctx.getSource().sendFailure(Component.literal("No encuentro \"" + query + "\", o el nombre vale para varios. Mira /dndchar list."));
			return 0;
		}
		//El nombre se lee ANTES de borrar: después, la hoja ya no está en memoria y el mensaje diría el id.
		String name = SheetLoader.nameOfCharacter(characterId);
		boolean wasNpc = SheetLoader.ownerOf(characterId, SheetLoader.getCharacterSheet(characterId)) == null;
		String error = SheetLoader.deleteCharacter(player, characterId, ctx.getSource().hasPermission(2));
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal("No se pudo borrar: ese personaje no existe o no es tuyo."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Personaje \"" + name + "\" borrado. Queda una copia en charactersheets/"
			+ characterId + SheetLoader.DELETED_SUFFIX + " por si te arrepientes.").withStyle(ChatFormatting.GREEN), false);
		//Un PNJ puede tener su cuerpo puesto en el mundo. Al quedarse sin ficha, Combatant.of lo degrada a
		//mob vanilla EN SILENCIO: sigue ahí, se le puede pegar, y ya no juega con ninguna regla. Decirlo es
		//más honesto que dejar al DM descubrirlo en mitad de un combate.
		if (wasNpc) {
			ctx.getSource().sendSuccess(() -> Component.literal("Si su cuerpo sigue en el mundo, bórralo con la Vara de DM: sin ficha ya no juega con reglas.")
				.withStyle(ChatFormatting.GRAY), false);
		}
		return 1;
	}

	//Lo tuyo, más los PNJ si eres DM: exactamente lo mismo que deleteCharacter va a aceptar, para no
	//sugerir un id que después se rechaza.
	private static List<String> deletableIds(CommandContext<CommandSourceStack> ctx) {
		List<String> ids = new ArrayList<>();
		try {
			ids.addAll(SheetLoader.charactersOf(ctx.getSource().getPlayerOrException().getStringUUID()));
		} catch (CommandSyntaxException ignored) {
			//Consola: no tiene personajes propios, solo puede tocar PNJ.
		}
		if (ctx.getSource().hasPermission(2)) ids.addAll(SheetLoader.npcIds());
		return ids;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String activeId = SheetLoader.activeCharacterOf(player.getStringUUID());
		List<String> owned = SheetLoader.charactersOf(player.getStringUUID());

		if (owned.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("No tienes ningún personaje todavía.").withStyle(ChatFormatting.GRAY), false);
			return 0;
		}

		ctx.getSource().sendSuccess(() -> Component.literal("Tus personajes:").withStyle(ChatFormatting.GOLD), false);
		for (String characterId : owned) {
			JsonObject sheet = SheetLoader.getCharacterSheet(characterId);
			String name = sheet != null && sheet.has("characterName") ? sheet.get("characterName").getAsString() : "(sin nombre)";
			boolean isActive = characterId.equals(activeId);
			//Mismo texto que sugiere el autocompletado, para que lo que se lee aquí sea literalmente lo que hay
			//que escribir. El id solo sale cuando dos personajes comparten nombre, que es cuando importa.
			String label = SheetLoader.suggestionLabelFor(owned, characterId);
			String suffix = label.equals(name) ? "  [" + characterId + "]" : "";
			ctx.getSource().sendSuccess(() -> Component.literal((isActive ? " ▶ " : "   ") + label + suffix)
				.withStyle(isActive ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		}
		return owned.size();
	}

	private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "nombre");
		String characterId = SheetLoader.createCharacter(player.getStringUUID(), name);
		//Se dice el comando exacto para ponérselo: crear sin activar es deliberado, pero sin esta línea
		//parecería que el comando no hizo nada.
		ctx.getSource().sendSuccess(() -> Component.literal("Personaje \"" + name + "\" creado. Ponértelo: /dndchar switch " + characterId)
			.withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int switchTo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String query = StringArgumentType.getString(ctx, "personaje");
		String characterId = SheetLoader.resolveCharacter(SheetLoader.charactersOf(player.getStringUUID()), query);
		if (characterId == null || !SheetLoader.switchCharacter(player, characterId)) {
			ctx.getSource().sendFailure(Component.literal("No encuentro \"" + query + "\" entre tus personajes, o el nombre vale para varios. Mira /dndchar list."));
			return 0;
		}
		JsonObject sheet = SheetLoader.getCharacterSheet(characterId);
		String name = sheet != null && sheet.has("characterName") ? sheet.get("characterName").getAsString() : characterId;
		ctx.getSource().sendSuccess(() -> Component.literal("Ahora llevas a " + name + ".").withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	//Solo fichas sin dueño: dar cuerpo al PJ de alguien que lo está jugando no tiene sentido, tendrías dos.
	private static List<String> npcIds() {
		return SheetLoader.npcIds();
	}

	/**
	 * <p>Le da cuerpo a una ficha de PNJ en el punto donde mira el DM. La entidad base es configurable
	 * porque un tabernero y un capitán de la guardia no deberían verse igual; por defecto un aldeano, que
	 * es lo que más se parece a "una persona".</p>
	 */
	private static int spawn(CommandContext<CommandSourceStack> ctx, String baseEntityId) throws CommandSyntaxException {
		String characterId = StringArgumentType.getString(ctx, "id");
		net.minecraft.world.phys.Vec3 pos = ctx.getSource().getPosition();
		net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();

		net.minecraft.world.entity.Entity spawned = SheetLoader.spawnNpc(level, pos.x, pos.y, pos.z, characterId, baseEntityId);
		if (spawned == null) {
			//Los dos motivos posibles se distinguen, en vez de un "no se pudo" que obliga a adivinar cuál es.
			ctx.getSource().sendFailure(Component.literal(
				SheetLoader.getCharacterSheet(characterId) == null
					? "No existe el personaje \"" + characterId + "\". Créalo con /dndchar npc <nombre>."
					: "\"" + baseEntityId + "\" no es un tipo de entidad válido."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Invocado " + spawned.getName().getString() + ".").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	private static int createNpc(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "nombre");
		String characterId = SheetLoader.createNpc(name);
		ctx.getSource().sendSuccess(() -> Component.literal("PNJ \"" + name + "\" creado con id " + characterId + ".")
			.withStyle(ChatFormatting.GREEN), true);
		return 1;
	}
}
