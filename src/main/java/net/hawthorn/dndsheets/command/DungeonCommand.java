package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DungeonManager;
import net.hawthorn.dndsheets.DungeonPieceRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Optional;

/**
 * <p>Comando de operador para el flujo de mazmorras (ver {@link DungeonManager} para el detalle de cada
 * paso). La GUI del Panel de DM llama a los mismos métodos de {@code DungeonManager}/{@code DungeonPieceRegistry}
 * a través de mensajes de red — este comando no duplica esa lógica, solo la expone en el chat.</p>
 */
@Mod.EventBusSubscriber
public class DungeonCommand {
	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dnddungeon")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("piece")
				.then(Commands.literal("capture")
					.then(Commands.argument("id", StringArgumentType.word())
						.then(Commands.argument("estructura", ResourceLocationArgument.id())
							.then(Commands.argument("pool", StringArgumentType.word())
								.then(Commands.argument("peso", IntegerArgumentType.integer(1, 150))
									.executes(DungeonCommand::capture))))))
				.then(Commands.literal("list").executes(DungeonCommand::list))
				.then(Commands.literal("remove")
					.then(Commands.argument("id", StringArgumentType.word())
						.executes(DungeonCommand::remove))))
			//Traer una construcción de fuera: sin pool la pega donde estás para que puedas entrar y ponerle
			//los jigsaw con la vara; con pool la registra directamente como pieza, que es lo que quieres
			//cuando el .nbt ya trae jigsaws (una estructura de vanilla o de un pack de mazmorras).
			.then(Commands.literal("import")
				.then(Commands.argument("archivo", StringArgumentType.string())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
						DndPaths.fileNames(DndPaths.STRUCTURES_DIR, ".nbt"), builder))
					.executes(DungeonCommand::importHere)
					.then(Commands.literal("pool")
						.then(Commands.argument("pool", StringArgumentType.word())
							.executes(ctx -> importAsPiece(ctx, 1))
							.then(Commands.argument("peso", IntegerArgumentType.integer(1, 150))
								.executes(ctx -> importAsPiece(ctx, IntegerArgumentType.getInteger(ctx, "peso"))))))))
			.then(Commands.literal("publish").executes(DungeonCommand::publish))
			.then(Commands.literal("generate")
				.then(Commands.argument("pool", StringArgumentType.word())
					.then(Commands.argument("maxDepth", IntegerArgumentType.integer(1, 7))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
							.executes(DungeonCommand::generate))))));
	}

	private static int capture(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String id = StringArgumentType.getString(ctx, "id");
		String structureId = ResourceLocationArgument.getId(ctx, "estructura").toString();
		String pool = StringArgumentType.getString(ctx, "pool");
		int weight = IntegerArgumentType.getInteger(ctx, "peso");

		//Mismo chequeo que ya hacía DungeonPieceCaptureMessage.handler (equivalente en GUI) — sin esto, el
		//comando era el único camino que dejaba pasar un pool con ".."/mayúsculas hasta DungeonManager,
		//justo lo que isValidPoolName existe para bloquear (ver su comentario: path traversal en publish()).
		if (!DungeonManager.isValidPoolName(pool)) {
			ctx.getSource().sendFailure(Component.literal(DungeonManager.poolNameError(pool)));
			return 0;
		}

		DungeonPieceRegistry.DungeonPiece piece = new DungeonPieceRegistry.DungeonPiece(id, structureId, pool, weight, "");
		Optional<String> error = DungeonManager.capturePiece(ctx.getSource().getServer(), piece);
		if (error.isPresent()) {
			ctx.getSource().sendFailure(Component.literal(error.get()));
			return 0;
		}

		ctx.getSource().sendSuccess(() -> Component.literal("Pieza \"" + id + "\" capturada en el pool \"" + pool + "\"."), true);
		return 1;
	}

	private static int importHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String fileName = StringArgumentType.getString(ctx, "archivo");
		ServerLevel level = ctx.getSource().getLevel();

		Optional<DungeonManager.Imported> imported = DungeonManager.importStructure(level, fileName,
			error -> ctx.getSource().sendFailure(Component.literal(error)));
		if (imported.isEmpty()) return 0;

		DungeonManager.Imported structure = imported.get();
		BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
		if (!DungeonManager.place(level, structure.structureId(), at)) {
			ctx.getSource().sendFailure(Component.literal("La estructura se importó pero no se pudo pegar aquí."));
			return 0;
		}

		//El aviso de los jigsaw es la mitad del valor del comando: una construcción exportada de un editor
		//no trae ninguno, y sin jigsaws una pieza no se puede enganchar a nada. Descubrirlo ahora es un
		//aviso; descubrirlo al generar es una mazmorra que no sale y ninguna pista de por qué.
		String jigsaws = structure.canConnect()
			? structure.jigsaws().size() + " jigsaw(s)" + (structure.canStart() ? ", incluido el de inicio" : "")
			: "sin jigsaws: ponlos con la vara de DM antes de capturarla, o no se podrá conectar con nada";
		ctx.getSource().sendSuccess(() -> Component.literal(structure.structureId() + " pegada aquí ("
			+ structure.width() + "x" + structure.height() + "x" + structure.depth() + ", " + jigsaws + ")."), true);
		return 1;
	}

	private static int importAsPiece(CommandContext<CommandSourceStack> ctx, int weight) throws CommandSyntaxException {
		String fileName = StringArgumentType.getString(ctx, "archivo");
		String pool = StringArgumentType.getString(ctx, "pool");
		ServerPlayer dm = ctx.getSource().getPlayerOrException();

		Optional<DungeonManager.Imported> imported = DungeonManager.importStructure(dm.serverLevel(), fileName,
			error -> ctx.getSource().sendFailure(Component.literal(error)));
		if (imported.isEmpty()) return 0;

		DungeonManager.Imported structure = imported.get();
		if (!structure.canConnect()) {
			ctx.getSource().sendFailure(Component.literal("Esa estructura no tiene ningún jigsaw, así que como pieza "
				+ "no se puede enganchar a nada. Pégala con /dnddungeon import \"" + fileName + "\", ponle los jigsaw "
				+ "con la vara de DM y captúrala con el bloque de estructura."));
			return 0;
		}

		String id = structure.structureId().getPath();
		Optional<String> error = DungeonManager.capturePiece(dm.server,
			new DungeonPieceRegistry.DungeonPiece(id, structure.structureId().toString(), pool, weight, ""));
		if (error.isPresent()) {
			ctx.getSource().sendFailure(Component.literal(error.get()));
			return 0;
		}

		String start = structure.canStart() ? " Tiene el jigsaw de inicio: puede abrir la mazmorra."
			: " No tiene el jigsaw de inicio, así que no puede ser la pieza de arranque.";
		ctx.getSource().sendSuccess(() -> Component.literal("Pieza \"" + id + "\" registrada en el pool \"" + pool
			+ "\" (peso " + weight + ")." + start), true);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		List<DungeonPieceRegistry.DungeonPiece> pieces = DungeonPieceRegistry.all();
		if (pieces.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("No hay piezas de mazmorra registradas."), false);
			return 0;
		}

		StringBuilder sb = new StringBuilder("Piezas (" + pieces.size() + "): ");
		for (DungeonPieceRegistry.DungeonPiece piece : pieces) {
			sb.append(piece.id()).append(" [").append(piece.pool()).append(", peso ").append(piece.weight()).append("]  ");
		}
		ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
		return pieces.size();
	}

	private static int remove(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		if (DungeonPieceRegistry.get(id) == null) {
			ctx.getSource().sendFailure(Component.literal("No conozco ninguna pieza \"" + id + "\"."));
			return 0;
		}

		DungeonManager.removePiece(ctx.getSource().getServer(), id);
		ctx.getSource().sendSuccess(() -> Component.literal("Pieza \"" + id + "\" borrada."), true);
		return 1;
	}

	private static int publish(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer dm = ctx.getSource().getPlayerOrException();
		String error = DungeonManager.publish(dm);
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}

		ctx.getSource().sendSuccess(() -> Component.literal("Pools de mazmorra publicados y recargados."), true);
		return 1;
	}

	private static int generate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer dm = ctx.getSource().getPlayerOrException();
		String pool = StringArgumentType.getString(ctx, "pool");
		int maxDepth = IntegerArgumentType.getInteger(ctx, "maxDepth");
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");

		//Mismo chequeo que ya hacía DungeonGenerateMessage.handler (equivalente en GUI) antes de llegar
		//acá — sin esto, un pool con mayúsculas o "/" mal puesto no fallaba con un mensaje claro por
		//comando: el StringArgumentType.word() de "pool" acepta A-Z (isValidPoolName no), así que llegaba
		//a construir un ResourceLocation inválido más abajo y reventaba con una excepción sin capturar en
		//vez del mismo aviso limpio que ya daba la GUI.
		if (!DungeonManager.isValidPoolName(pool)) {
			ctx.getSource().sendFailure(Component.literal(DungeonManager.poolNameError(pool)));
			return 0;
		}

		boolean success = DungeonManager.generate(dm, pool, maxDepth, pos);
		if (!success) return 0;

		ctx.getSource().sendSuccess(() -> Component.literal("Mazmorra generada en " + pos.toShortString() + "."), true);
		return 1;
	}
}
