package net.hawthorn.dndsheets.dungeon.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.dungeon.DungeonManager;
import net.hawthorn.dndsheets.dungeon.DungeonPieceRegistry;
import net.hawthorn.dndsheets.dungeon.network.DungeonPieceListMessage;
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
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * <p>Operator command for the dungeon workflow (see {@link DungeonManager} for the detail of each
 * step). The DM Panel GUI calls the same {@code DungeonManager}/{@code DungeonPieceRegistry} methods
 * via network messages — this command doesn't duplicate that logic, it just exposes it in chat.</p>
 */
@Mod.EventBusSubscriber
public class DungeonCommand {
	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dnddungeon")
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(Commands.literal("piece")
				.then(Commands.literal("capture")
					.then(Commands.argument("id", StringArgumentType.word())
						.then(Commands.argument("structure", ResourceLocationArgument.id())
							.then(Commands.argument("pool", StringArgumentType.word())
								.then(Commands.argument("weight", IntegerArgumentType.integer(1, 150))
									.executes(DungeonCommand::capture))))))
				.then(Commands.literal("list").executes(DungeonCommand::list))
				.then(Commands.literal("remove")
					.then(Commands.argument("id", StringArgumentType.word())
						.executes(DungeonCommand::remove))))
			//Bring in a build from outside: without a pool it pastes it where you're standing so you can
			//walk in and add the jigsaws with the wand; with a pool it registers it directly as a piece,
			//which is what you want when the .nbt already has jigsaws (a vanilla structure or one from a
			//dungeon pack).
			.then(Commands.literal("import")
				.then(Commands.argument("file", StringArgumentType.string())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
						DndPaths.fileNames(DndPaths.STRUCTURES_DIR, ".nbt"), builder))
					.executes(DungeonCommand::importHere)
					.then(Commands.literal("pool")
						.then(Commands.argument("pool", StringArgumentType.word())
							.executes(ctx -> importAsPiece(ctx, 1))
							.then(Commands.argument("weight", IntegerArgumentType.integer(1, 150))
								.executes(ctx -> importAsPiece(ctx, IntegerArgumentType.getInteger(ctx, "weight"))))))))
			.then(Commands.literal("publish").executes(DungeonCommand::publish))
			.then(Commands.literal("generate")
				.then(Commands.argument("pool", StringArgumentType.word())
					.then(Commands.argument("maxDepth", IntegerArgumentType.integer(1, 7))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
							.executes(DungeonCommand::generate)))))
			//Entry point for the DM Panel (core): it used to send DungeonPieceListRequestMessage
			//directly over this addon's channel, which the core can no longer do without depending on
			//it. The Panel sends this command via chat instead (same pattern as "journal"), and this
			//responds with exactly what that message used to respond with.
			.then(Commands.literal("gui").executes(DungeonCommand::gui)));
	}

	private static int gui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		DndsheetsDungeonMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			DungeonPieceListMessage.of(player.serverLevel(), DungeonPieceRegistry.all()));
		return 1;
	}

	private static int capture(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String id = StringArgumentType.getString(ctx, "id");
		String structureId = ResourceLocationArgument.getId(ctx, "structure").toString();
		String pool = StringArgumentType.getString(ctx, "pool");
		int weight = IntegerArgumentType.getInteger(ctx, "weight");

		//Same check that DungeonPieceCaptureMessage.handler already did (its GUI equivalent) — without this,
		//the command was the only path that let a pool with ".."/uppercase letters reach DungeonManager,
		//exactly what isValidPoolName exists to block (see its comment: path traversal in publish()).
		if (!DungeonManager.isValidPoolName(pool)) {
			ctx.getSource().sendFailure((DungeonManager.poolNameError(pool)));
			return 0;
		}

		DungeonPieceRegistry.DungeonPiece piece = new DungeonPieceRegistry.DungeonPiece(id, structureId, pool, weight, "");
		Optional<String> error = DungeonManager.capturePiece(ctx.getSource().getServer(), piece);
		if (error.isPresent()) {
			ctx.getSource().sendFailure(Component.literal(error.get()));
			return 0;
		}

		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.dungeon.piece_captured", id, pool), true);
		return 1;
	}

	private static int importHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String fileName = StringArgumentType.getString(ctx, "file");
		ServerLevel level = ctx.getSource().getLevel();

		Optional<DungeonManager.Imported> imported = DungeonManager.importStructure(level, fileName,
			error -> ctx.getSource().sendFailure(Component.literal(error)));
		if (imported.isEmpty()) return 0;

		DungeonManager.Imported structure = imported.get();
		BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
		if (!DungeonManager.place(level, structure.structureId(), at)) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.dungeon.paste_failed"));
			return 0;
		}

		//The jigsaw notice is half the value of this command: a build exported from an editor brings none,
		//and without jigsaws a piece can't hook into anything. Finding this out now is a warning; finding
		//it out when generating is a dungeon that doesn't come out and no clue why.
		net.minecraft.network.chat.Component jigsaws = structure.canConnect()
			? Component.translatable("chat.dndsheets.dungeon.import_jigsaw_count", structure.jigsaws().size())
				.append(structure.canStart() ? Component.translatable("chat.dndsheets.dungeon.import_includes_start") : Component.empty())
			: Component.translatable("chat.dndsheets.dungeon.import_no_jigsaws");
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.dungeon.imported_here",
			structure.structureId().toString(), structure.width(), structure.height(), structure.depth(), jigsaws), true);
		return 1;
	}

	private static int importAsPiece(CommandContext<CommandSourceStack> ctx, int weight) throws CommandSyntaxException {
		String fileName = StringArgumentType.getString(ctx, "file");
		String pool = StringArgumentType.getString(ctx, "pool");
		ServerPlayer dm = ctx.getSource().getPlayerOrException();

		Optional<DungeonManager.Imported> imported = DungeonManager.importStructure(dm.serverLevel(), fileName,
			error -> ctx.getSource().sendFailure(Component.literal(error)));
		if (imported.isEmpty()) return 0;

		DungeonManager.Imported structure = imported.get();
		if (!structure.canConnect()) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.dungeon.no_jigsaws_for_piece", fileName));
			return 0;
		}

		String id = structure.structureId().getPath();
		Optional<String> error = DungeonManager.capturePiece(dm.server,
			new DungeonPieceRegistry.DungeonPiece(id, structure.structureId().toString(), pool, weight, ""));
		if (error.isPresent()) {
			ctx.getSource().sendFailure(Component.literal(error.get()));
			return 0;
		}

		net.minecraft.network.chat.Component start = structure.canStart()
			? Component.translatable("chat.dndsheets.dungeon.import_can_start")
			: Component.translatable("chat.dndsheets.dungeon.import_cannot_start");
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.dungeon.piece_registered", id, pool, weight, start), true);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		List<DungeonPieceRegistry.DungeonPiece> pieces = DungeonPieceRegistry.all();
		if (pieces.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.dungeon.no_pieces"), false);
			return 0;
		}

		net.minecraft.network.chat.MutableComponent msg = Component.translatable("chat.dndsheets.dungeon.piece_list_header", pieces.size());
		for (DungeonPieceRegistry.DungeonPiece piece : pieces) {
			msg = msg.append(Component.literal(piece.id() + " [" + piece.pool() + ", "))
				.append(Component.translatable("chat.dndsheets.dungeon.piece_list_weight", piece.weight()))
				.append(Component.literal("]  "));
		}
		net.minecraft.network.chat.Component finalMsg = msg;
		ctx.getSource().sendSuccess(() -> finalMsg, false);
		return pieces.size();
	}

	private static int remove(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		if (DungeonPieceRegistry.get(id) == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.dungeon.no_such_piece", id));
			return 0;
		}

		DungeonManager.removePiece(ctx.getSource().getServer(), id);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.dungeon.piece_deleted", id), true);
		return 1;
	}

	private static int publish(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer dm = ctx.getSource().getPlayerOrException();
		String error = DungeonManager.publish(dm);
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}

		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.dungeon.published"), true);
		return 1;
	}

	private static int generate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer dm = ctx.getSource().getPlayerOrException();
		String pool = StringArgumentType.getString(ctx, "pool");
		int maxDepth = IntegerArgumentType.getInteger(ctx, "maxDepth");
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");

		//Same check that DungeonGenerateMessage.handler already did (its GUI equivalent) before reaching
		//here — without this, a pool with uppercase letters or a misplaced "/" didn't fail with a clear
		//message via command: StringArgumentType.word() for "pool" accepts A-Z (isValidPoolName doesn't),
		//so it would go on to build an invalid ResourceLocation further down and blow up with an uncaught
		//exception instead of the same clean warning the GUI already gave.
		if (!DungeonManager.isValidPoolName(pool)) {
			ctx.getSource().sendFailure((DungeonManager.poolNameError(pool)));
			return 0;
		}

		boolean success = DungeonManager.generate(dm, pool, maxDepth, pos);
		if (!success) return 0;

		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.dungeon.generated", pos.toShortString()), true);
		return 1;
	}
}
