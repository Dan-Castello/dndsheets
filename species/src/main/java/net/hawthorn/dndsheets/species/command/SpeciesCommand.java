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
 * <p>Punto de entrada del jugador para las tres capas de Origins que este addon llena con el SRD: Raza
 * ({@code dndsheets_species:race}), Clase ({@code origins-classes:class}, del addon de terceros
 * Origins:Classes, reemplazada entera) y Trasfondo ({@code dndsheets_species:background}). Sin
 * selectores propios, sin mensajes de red propios — todo por chat, a pedido explícito, nunca por tick.</p>
 *
 * <p><b>{@code choose}/{@code chooseclass}/{@code choosebackground} son el punto de entrada real</b> —
 * los botones "Raza"/"Trasfondo" de la ficha los usan. Abren el selector real de Origins
 * ({@code /origin gui @s <capa>}, comando real de Origins — ver {@link #openOriginGui}) y programan un
 * {@code sync} automático {@value #AUTO_SYNC_DELAY_TICKS} ticks después (~{@value #AUTO_SYNC_DELAY_SECONDS}
 * segundos: tiempo de sobra para mirar la lista y confirmar), sin que el jugador tenga que acordarse de
 * volver a apretar nada. Antes de esto un clic solo leía lo que YA hubiera elegido — para un personaje sin
 * nada elegido, o para cambiar de opinión con uno que ya tenía algo aplicado, no había ninguna forma de
 * llegar al selector real, solo de releer una y otra vez el mismo valor viejo.</p>
 *
 * <p>{@code sync}/{@code syncclass}/{@code syncbackground} siguen disponibles sueltos (recorrer el flujo
 * a mano, o forzar una relectura sin reabrir el selector).</p>
 *
 * <p>ponytail: el retraso es una espera fija, no un evento de "elegiste algo" — Origins no expone
 * ninguno. Se verifica que el jugador siga en el MISMO personaje antes de aplicar, para no escribirle la
 * elección a otro si cambia de personaje en esos segundos; si algún día Origins expone un callback de
 * verdad, esta espera es lo primero que se puede borrar.</p>
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
				.then(Commands.argument("archivo", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(DndPaths.RACES_DIR), builder))
					.executes(SpeciesCommand::load)))
			.then(Commands.literal("loadbackground")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("archivo", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(DndPaths.BACKGROUNDS_DIR), builder))
					.executes(SpeciesCommand::loadBackground))));
	}

	/** {@code /origin gui @s <capa>}: comando real de Origins (verificado en su propio bytecode), no
	 *  inventado. Corre con el mismo {@link CommandSourceStack} del jugador — sin escalar permisos. */
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

		//Mismo personaje al que le pertenecía el clic, no el que esté activo cuando el retraso termine —
		//si el jugador cambia de personaje mientras el selector de Origins sigue abierto, la sincronización
		//automática no debe escribirle la elección al que quedó activo después.
		String characterId = SheetLoader.activeCharacterOf(player.getStringUUID());
		DndsheetsMod.queueServerWork(AUTO_SYNC_DELAY_TICKS, () -> {
			if (player.isRemoved() || !characterId.equals(SheetLoader.activeCharacterOf(player.getStringUUID()))) return;
			autoSync.run(player);
		});
		return 1;
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "archivo");
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
		String fileName = StringArgumentType.getString(ctx, "archivo");
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
			//Personaje nuevo, todavía sin nada elegido en esta capa: en vez de solo avisar, se lo abre
			//directo — es la misma pregunta que ya se hacía manualmente con /origin gui.
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

		//Invariante 4 de PROJECT_CONTEXT.md: toda mutación de hoja con jugador delante persiste. Mismo
		//camino que usa el resto del mod (ver ConcentrationManager.notifyClient en el core).
		SheetLoader.saveServer(sheet, player.getStringUUID());
		//La ficha ENTERA, no un parche (ver PresetManager.applyPreset): CharacterSheetScreen.refreshIfOpen
		//ignora a propósito los parches de SheetFieldUpdateMessage (llegan a mitad de combate y no deben
		//tapar lo que el jugador esté escribiendo), así que un parche dejaba la pantalla abierta con el
		//valor viejo hasta cerrarla y reabrirla — esto sí repinta.
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
		//Ficha entera, mismo motivo que en sync() de arriba.
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

		//PresetManager.applyPreset ya hace todo: dado de golpe, características, equipo inicial, y le
		//manda la hoja completa al cliente — no hay nada que reimplementar acá.
		PresetManager.applyPreset(player, classId.get());

		//Invariante 4 (ver sync() arriba): applyPreset no persiste por sí solo, solo muta en memoria y
		//avisa al cliente.
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet != null) SheetLoader.saveServer(sheet, player.getStringUUID());

		player.sendSystemMessage(Component.translatable("chat.dndsheets_species.class_applied", preset.name()));
		return 1;
	}
}
