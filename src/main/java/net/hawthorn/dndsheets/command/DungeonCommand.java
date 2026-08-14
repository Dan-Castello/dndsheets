package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DungeonManager;
import net.hawthorn.dndsheets.DungeonPieceRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

		DungeonPieceRegistry.DungeonPiece piece = new DungeonPieceRegistry.DungeonPiece(id, structureId, pool, weight, "");
		Optional<String> error = DungeonManager.capturePiece(ctx.getSource().getServer(), piece);
		if (error.isPresent()) {
			ctx.getSource().sendFailure(Component.literal(error.get()));
			return 0;
		}

		ctx.getSource().sendSuccess(() -> Component.literal("Pieza \"" + id + "\" capturada en el pool \"" + pool + "\"."), true);
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

		boolean success = DungeonManager.generate(dm, pool, maxDepth, pos);
		if (!success) return 0;

		ctx.getSource().sendSuccess(() -> Component.literal("Mazmorra generada en " + pos.toShortString() + "."), true);
		return 1;
	}
}
