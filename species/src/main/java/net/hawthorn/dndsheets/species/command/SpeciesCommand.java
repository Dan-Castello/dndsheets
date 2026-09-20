package net.hawthorn.dndsheets.species.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.PresetManager;
import net.hawthorn.dndsheets.PresetRegistry;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.hawthorn.dndsheets.species.BackgroundRegistry;
import net.hawthorn.dndsheets.species.OriginBridge;
import net.hawthorn.dndsheets.species.RaceRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * <p>Player entry point for the three Origins layers this addon fills with the SRD: Race
 * ({@code dndsheets_species:race}), Class ({@code origins-classes:class}, from the third-party
 * Origins:Classes addon, replaced entirely) and Background ({@code dndsheets_species:background}). No
 * custom selectors, no custom network messages — all via chat, on explicit request, never on tick.</p>
 *
 * <p><b>{@code choose}/{@code chooseclass}/{@code choosebackground} are the real entry point</b> —
 * the sheet's "Race"/"Background" buttons use them. They open the real Origins selector
 * ({@code /origin gui @s <layer>}, a real Origins command — see {@link #openOriginGui}) and schedule an
 * automatic {@code sync} {@value #AUTO_SYNC_DELAY_TICKS} ticks later (~{@value #AUTO_SYNC_DELAY_SECONDS}
 * seconds: plenty of time to look at the list and confirm), without the player having to remember to
 * press anything again. Before this, a click only read whatever had ALREADY been chosen — for a
 * character with nothing chosen yet, or to change one's mind on a character that already had something
 * applied, there was no way to reach the real selector, only to re-read the same old value over and
 * over.</p>
 *
 * <p>{@code sync}/{@code syncclass}/{@code syncbackground} remain available standalone (walking the flow
 * by hand, or forcing a re-read without reopening the selector).</p>
 *
 * <p>ponytail: the delay is a fixed wait, not an "you chose something" event — Origins exposes none. It
 * verifies the player is still on the SAME character before applying, so as not to write the choice to
 * another one if they switch characters within those seconds; if Origins ever exposes a real callback,
 * this wait is the first thing that can be deleted.</p>
 */
@Mod.EventBusSubscriber
public class SpeciesCommand {
	private static final ResourceLocation RACE_LAYER = new ResourceLocation("dndsheets_species", "race");
	private static final ResourceLocation BACKGROUND_LAYER = new ResourceLocation("dndsheets_species", "background");
	private static final ResourceLocation CLASS_LAYER = new ResourceLocation("origins-classes", "class");

	private static final int AUTO_SYNC_DELAY_TICKS = 200;
	private static final int AUTO_SYNC_DELAY_SECONDS = AUTO_SYNC_DELAY_TICKS / 20;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndspecies")
			.then(Commands.literal("sync").executes(ctx -> sync(ctx.getSource().getPlayerOrException())))
			.then(Commands.literal("syncclass").executes(ctx -> syncClass(ctx.getSource().getPlayerOrException())))
			.then(Commands.literal("syncbackground").executes(ctx -> syncBackground(ctx.getSource().getPlayerOrException())))
			.then(Commands.literal("choose").executes(ctx -> choose(ctx, RACE_LAYER, SpeciesCommand::sync)))
			.then(Commands.literal("chooseclass").executes(ctx -> choose(ctx, CLASS_LAYER, SpeciesCommand::syncClass)))
			.then(Commands.literal("choosebackground").executes(ctx -> choose(ctx, BACKGROUND_LAYER, SpeciesCommand::syncBackground)))
			.then(Commands.literal("load")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("file", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(DndPaths.RACES_DIR), builder))
					.executes(SpeciesCommand::load)))
			.then(Commands.literal("loadbackground")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("file", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(DndPaths.BACKGROUNDS_DIR), builder))
					.executes(SpeciesCommand::loadBackground))));
	}

	/** {@code /origin gui @s <layer>}: a real Origins command (verified in its own bytecode), not
	 *  made up. Runs with the player's own {@link CommandSourceStack} — no permission escalation. */
	private static void openOriginGui(CommandSourceStack source, ResourceLocation layerId) {
		source.getServer().getCommands().performPrefixedCommand(source, "origin gui @s " + layerId);
	}

	@FunctionalInterface
	private interface AutoSync {
		void run(ServerPlayer player);
	}

	private static int choose(CommandContext<CommandSourceStack> ctx, ResourceLocation layerId, AutoSync autoSync) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		openOriginGui(ctx.getSource(), layerId);

		//The same character the click belonged to, not whichever is active when the delay ends — if the
		//player switches characters while the Origins selector is still open, the automatic sync must not
		//write the choice to whichever one ended up active afterward.
		String characterId = SheetLoader.activeCharacterOf(player.getStringUUID());
		DndsheetsMod.queueServerWork(AUTO_SYNC_DELAY_TICKS, () -> {
			if (player.isRemoved() || !characterId.equals(SheetLoader.activeCharacterOf(player.getStringUUID()))) return;
			autoSync.run(player);
		});
		return 1;
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "file");
		Path file = DndPaths.RACES_DIR.resolve(fileName + ".json");
		try {
			int count = RaceRegistry.loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets_species.loaded", count), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets_species.load_failed", e.getMessage()));
			return 0;
		}
	}

	private static int loadBackground(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "file");
		Path file = DndPaths.BACKGROUNDS_DIR.resolve(fileName + ".json");
		try {
			int count = BackgroundRegistry.loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets_species.loaded", count), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets_species.load_failed", e.getMessage()));
			return 0;
		}
	}

	private static int sync(ServerPlayer player) {
		Optional<String> raceId = OriginBridge.chosenOriginId(player, RACE_LAYER);
		if (raceId.isEmpty()) {
			//New character, nothing chosen yet in this layer: instead of just warning, it's opened
			//directly — it's the same prompt that used to be done manually with /origin gui.
			player.sendSystemMessage(Component.translatable("chat.dndsheets_species.no_origin").withStyle(net.minecraft.ChatFormatting.RED));
			openOriginGui(player.createCommandSourceStack(), RACE_LAYER);
			return 0;
		}

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets_species.no_sheet").withStyle(net.minecraft.ChatFormatting.RED));
			return 0;
		}

		RaceRegistry.ApplyResult result = RaceRegistry.apply(sheet, raceId.get());
		if (result.outcome() == RaceRegistry.ApplyOutcome.UNKNOWN_RACE) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets_species.unknown_race", raceId.get()).withStyle(net.minecraft.ChatFormatting.RED));
			return 0;
		}
		if (result.outcome() == RaceRegistry.ApplyOutcome.NO_CHANGE) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets_species.no_change", result.race().name()));
			return 1;
		}

		//Invariant 4 from PROJECT_CONTEXT.md: every sheet mutation done with the player present persists.
		//Same path the rest of the mod uses (see ConcentrationManager.notifyClient in the core).
		SheetLoader.saveServer(sheet, player.getStringUUID());
		//The WHOLE sheet, not a patch (see PresetManager.applyPreset): CharacterSheetScreen.refreshIfOpen
		//deliberately ignores SheetFieldUpdateMessage patches (they arrive mid-combat and must not
		//overwrite what the player is typing), so a patch would leave the screen open with the old value
		//until closed and reopened — this does repaint it.
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

		player.sendSystemMessage(Component.translatable("chat.dndsheets_species.applied", result.race().name()));
		if (result.race().note() != null) {
			player.sendSystemMessage(Component.literal(result.race().note()));
		}
		return 1;
	}

	private static int syncBackground(ServerPlayer player) {
		Optional<String> backgroundId = OriginBridge.chosenOriginId(player, BACKGROUND_LAYER);
		if (backgroundId.isEmpty()) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets_species.no_background_origin").withStyle(net.minecraft.ChatFormatting.RED));
			openOriginGui(player.createCommandSourceStack(), BACKGROUND_LAYER);
			return 0;
		}

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets_species.no_sheet").withStyle(net.minecraft.ChatFormatting.RED));
			return 0;
		}

		BackgroundRegistry.ApplyResult result = BackgroundRegistry.apply(sheet, backgroundId.get());
		if (result.outcome() == BackgroundRegistry.ApplyOutcome.UNKNOWN_BACKGROUND) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets_species.unknown_background", backgroundId.get()).withStyle(net.minecraft.ChatFormatting.RED));
			return 0;
		}
		if (result.outcome() == BackgroundRegistry.ApplyOutcome.NO_CHANGE) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets_species.no_change_background", result.background().name()));
			return 1;
		}

		SheetLoader.saveServer(sheet, player.getStringUUID());
		//Whole sheet, same reason as in sync() above.
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

		player.sendSystemMessage(Component.translatable("chat.dndsheets_species.background_applied", result.background().name()));
		return 1;
	}

	private static int syncClass(ServerPlayer player) {
		Optional<String> classId = OriginBridge.chosenOriginId(player, CLASS_LAYER);
		if (classId.isEmpty()) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets_species.no_class_origin").withStyle(net.minecraft.ChatFormatting.RED));
			openOriginGui(player.createCommandSourceStack(), CLASS_LAYER);
			return 0;
		}
		PresetRegistry.ClassPreset preset = PresetRegistry.get(classId.get());
		if (preset == null) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets_species.unknown_class", classId.get()).withStyle(net.minecraft.ChatFormatting.RED));
			return 0;
		}

		//PresetManager.applyPreset already does everything: hit die, ability scores, starting equipment,
		//and sends the full sheet to the client — nothing to reimplement here.
		PresetManager.applyPreset(player, classId.get());

		//Invariant 4 (see sync() above): applyPreset doesn't persist on its own, it only mutates in
		//memory and notifies the client.
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet != null) SheetLoader.saveServer(sheet, player.getStringUUID());

		player.sendSystemMessage(Component.translatable("chat.dndsheets_species.class_applied", preset.name()));
		return 1;
	}
}
