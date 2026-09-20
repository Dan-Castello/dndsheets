package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.JournalManager;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.List;

/**
 * <p>{@code /dndjournal}: campaign journal and handouts, which are the same thing with different
 * visibility (see {@link JournalManager}).</p>
 *
 * <ul>
 *   <li>{@code publish <title>} — turns the Book and Quill in your hand into an entry. DM only.</li>
 *   <li>{@code share <id> <players>} — delivers it to those players. That's what a handout does.</li>
 *   <li>{@code party <id>} / {@code hide <id>} — visible to everyone, or back to private.</li>
 *   <li>{@code list} — opens the journal with whatever YOU can read. No permissions: everyone sees their
 *   own.</li>
 * </ul>
 */
@Mod.EventBusSubscriber
public class JournalCommand {

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndjournal")
			//No subcommand opens the journal: that's what will be wanted almost always.
			.executes(JournalCommand::open)
			.then(Commands.literal("list").executes(JournalCommand::open))
			.then(Commands.literal("publish")
				.requires(source -> DndsheetsMod.canActAsDm(source))
				.then(Commands.argument("title", StringArgumentType.greedyString())
					.executes(JournalCommand::publish)))
			.then(Commands.literal("share")
				.requires(source -> DndsheetsMod.canActAsDm(source))
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(JournalCommand::suggestIds)
					.then(Commands.argument("players", EntityArgument.players())
						.executes(JournalCommand::share))))
			.then(Commands.literal("party")
				.requires(source -> DndsheetsMod.canActAsDm(source))
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(JournalCommand::suggestIds)
					.executes(ctx -> setParty(ctx, true))))
			.then(Commands.literal("hide")
				.requires(source -> DndsheetsMod.canActAsDm(source))
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(JournalCommand::suggestIds)
					.executes(ctx -> setParty(ctx, false))))
			.then(Commands.literal("delete")
				.requires(source -> DndsheetsMod.canActAsDm(source))
				.then(Commands.argument("id", StringArgumentType.word())
					.suggests(JournalCommand::suggestIds)
					.executes(JournalCommand::delete))));
	}

	//Only suggests what whoever's typing can read: autocompleting the id of a private DM note would
	//already leak that it exists, which is exactly what a private note shouldn't reveal.
	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestIds(
			CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		try {
			List<String> ids = JournalManager.readableBy(ctx.getSource().getPlayerOrException())
				.stream().map(JournalManager.Entry::id).toList();
			return SharedSuggestionProvider.suggest(ids, builder);
		} catch (CommandSyntaxException e) {
			return builder.buildFuture();
		}
	}

	private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		//The command already runs on the server: it sends the list directly, without asking itself for it.
		BrowseActionMessage.sendJournal(ctx.getSource().getPlayerOrException());
		return 1;
	}

	private static int publish(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer dm = ctx.getSource().getPlayerOrException();
		String title = StringArgumentType.getString(ctx, "title");

		ItemStack book = dm.getMainHandItem().is(Items.WRITABLE_BOOK) ? dm.getMainHandItem()
			: dm.getOffhandItem().is(Items.WRITABLE_BOOK) ? dm.getOffhandItem() : ItemStack.EMPTY;
		if (book.isEmpty()) {
			//What's missing is stated exactly: "couldn't do it" would force guessing between not carrying
			//a book, carrying a signed one, or carrying a blank one.
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.journal.needs_book"));
			return 0;
		}

		JournalManager.Entry entry = JournalManager.publishFromBook(dm, book, title);
		if (entry == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.journal.blank_book"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.journal.published", entry.title(), entry.id())
			.withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int share(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String id = StringArgumentType.getString(ctx, "id");
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		if (!JournalManager.share(id, targets)) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.journal.no_entry"));
			return 0;
		}
		JournalManager.Entry entry = JournalManager.get(id);
		//Whoever receives it is notified: a handout that just appears in a list without saying anything
		//goes unread.
		for (ServerPlayer target : targets) {
			target.sendSystemMessage(Component.translatable("chat.dndsheets.journal.received", entry.title()).withStyle(ChatFormatting.GOLD));
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.journal.shared_with", targets.size()), false);
		return targets.size();
	}

	private static int setParty(CommandContext<CommandSourceStack> ctx, boolean party) {
		String id = StringArgumentType.getString(ctx, "id");
		if (!JournalManager.setParty(id, party)) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.journal.no_entry"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable(party
			? "chat.dndsheets.journal.party_visible" : "chat.dndsheets.journal.party_hidden"), false);
		return 1;
	}

	private static int delete(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		if (!JournalManager.delete(id)) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.journal.no_entry"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.journal.deleted"), false);
		return 1;
	}
}
