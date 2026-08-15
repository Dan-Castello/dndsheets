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
			.then(Commands.literal("switch")
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ownedIds(ctx), builder))
					.executes(CharacterCommand::switchTo)))
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
	private static List<String> ownedIds(CommandContext<CommandSourceStack> ctx) {
		try {
			return SheetLoader.charactersOf(ctx.getSource().getPlayerOrException().getStringUUID());
		} catch (CommandSyntaxException e) {
			return List.of();
		}
	}

	//El servidor ya sabe qué personajes tiene: manda la lista directamente, sin que el cliente tenga que
	//pedirla primero. La ida y vuelta solo hace falta desde un botón de GUI (ver RosterActionMessage).
	private static int openScreen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		net.hawthorn.dndsheets.network.RosterActionMessage.sendOwnCharacters(ctx.getSource().getPlayerOrException());
		return 1;
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
			ctx.getSource().sendSuccess(() -> Component.literal((isActive ? " ▶ " : "   ") + name + "  [" + characterId + "]")
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
		String characterId = StringArgumentType.getString(ctx, "id");
		if (!SheetLoader.switchCharacter(player, characterId)) {
			ctx.getSource().sendFailure(Component.literal("Ese personaje no existe o no es tuyo. Mira /dndchar list."));
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
