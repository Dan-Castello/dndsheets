package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.TurnManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;

/**
 * <p>Turn mode: {@code /dndturns start} rolls initiative (1d20 + Dexterity) for every player and
 * spawned monster within a radius, sorts them and starts (see {@link TurnManager#startAt}).
 * {@code /dndturns next} advances to the next combatant; {@code cancel} does the same thing (to skip an
 * AFK combatant without them acting); {@code end} turns it off. Every combatant is entitled to a single
 * action on their turn — see {@link TurnManager#tryAct}. {@code effect} applies a status effect (poison,
 * etc.) to a player by hand, which {@link TurnManager} will keep rolling on its own at the start of each
 * of their turns.</p>
 */
@Mod.EventBusSubscriber
public class TurnCommand {
	//Tab suggestions only: any other text is still valid, this doesn't restrict the argument.
	//5e's 14 conditions first (those DO have real mechanical consequences, see Condition and
	//TurnManager.applyEffect), then the usual free-name damage effects behind them. Still doesn't
	//restrict the argument: any other text works and behaves as a damage-over-time timer.
	private static final String[] EFFECT_NAME_SUGGESTIONS = buildEffectSuggestions();

	private static String[] buildEffectSuggestions() {
		java.util.List<String> names = new java.util.ArrayList<>();
		for (net.hawthorn.dndsheets.Condition condition : net.hawthorn.dndsheets.Condition.values()) names.add(condition.label());
		names.addAll(java.util.List.of("poison", "fire", "bleeding"));
		return names.toArray(new String[0]);
	}
	private static final String[] DICE_SUGGESTIONS = {"1d4", "1d6", "1d8", "1d10", "1d12", "2d6", "2d8"};

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndturns")
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(Commands.literal("start")
				.executes(ctx -> start(ctx, TurnManager.DEFAULT_RADIUS))
				.then(Commands.argument("radius", IntegerArgumentType.integer(1, 200))
					.executes(ctx -> start(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
			.then(Commands.literal("next").executes(TurnCommand::next))
			.then(Commands.literal("cancel").executes(TurnCommand::cancel))
			.then(Commands.literal("end").executes(TurnCommand::end))
			.then(Commands.literal("effect")
				.then(Commands.argument("players", EntityArgument.players())
					.then(Commands.argument("name", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(EFFECT_NAME_SUGGESTIONS, builder))
						.then(Commands.argument("dice", StringArgumentType.word())
							.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DICE_SUGGESTIONS, builder))
							.then(Commands.argument("turns", IntegerArgumentType.integer(1, 20))
								.executes(TurnCommand::applyEffect)))))));
	}

	private static int start(CommandContext<CommandSourceStack> ctx, double radius) {
		int count = TurnManager.startAt(ctx.getSource().getLevel(), ctx.getSource().getPosition(), radius);
		if (count == 0) ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.turn.nobody_in_radius"));
		return count;
	}

	private static int next(CommandContext<CommandSourceStack> ctx) {
		TurnManager.next(ctx.getSource().getLevel());
		return 1;
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx) {
		TurnManager.cancel(ctx.getSource().getLevel());
		return 1;
	}

	private static int end(CommandContext<CommandSourceStack> ctx) {
		TurnManager.end(ctx.getSource().getLevel());
		return 1;
	}

	private static int applyEffect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String name = StringArgumentType.getString(ctx, "name");
		String dice = StringArgumentType.getString(ctx, "dice");
		int turns = IntegerArgumentType.getInteger(ctx, "turns");

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) {
			TurnManager.applyEffect(target, name, dice, turns);
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.turn.effect_applied_to_players", name, targets.size()), true);
		return targets.size();
	}
}
