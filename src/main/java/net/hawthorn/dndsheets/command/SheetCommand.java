package net.hawthorn.dndsheets.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.BarbarianRageManager;
import net.hawthorn.dndsheets.BardInspirationManager;
import net.hawthorn.dndsheets.CounterspellManager;
import net.hawthorn.dndsheets.DiceManager;
import net.hawthorn.dndsheets.LevelUpManager;
import net.hawthorn.dndsheets.SpellSlots;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.DruidWildShapeManager;
import net.hawthorn.dndsheets.FighterSecondWindManager;
import net.hawthorn.dndsheets.PaladinSmiteManager;
import net.hawthorn.dndsheets.RangerHunterMarkManager;
import net.hawthorn.dndsheets.ShieldManager;
import net.hawthorn.dndsheets.SorcererMetamagicManager;
import net.hawthorn.dndsheets.PassiveScores;
import net.hawthorn.dndsheets.RestManager;
import net.hawthorn.dndsheets.RollIndex;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.TurnItemManager;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * <p>Utilidades de administración de hoja que no tenían dueño natural en ningún otro comando.
 * {@code /dndsheet setslots} tapa el hueco más urgente de la auditoría: antes de esto,
 * {@code spellSlotsMax} nunca se escribía en ningún sitio salvo el 0 por defecto, así que el Grimorio
 * era inutilizable sin editar el JSON de la hoja a mano en el disco.</p>
 */
@Mod.EventBusSubscriber
public class SheetCommand {
	//Solo sugerencias de tab (los 13 tipos de daño de 5e en español): cualquier otro texto sigue siendo
	//válido, pero para que DamageTypes.multiplierFor coincida de verdad con lo que digan las armas/
	//hechizos, conviene escribir siempre el mismo nombre exacto — de ahí que valga la pena sugerirlo.
	private static final String[] DAMAGE_TYPE_SUGGESTIONS = {
		"fisico", "cortante", "perforante", "contundente", "fuego", "frio", "rayo",
		"acido", "veneno", "psiquico", "radiante", "necrotico", "fuerza", "trueno"
	};

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndsheet")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("setslots")
				.then(Commands.argument("jugadores", EntityArgument.players())
					.then(Commands.argument("maximo", IntegerArgumentType.integer())
						.executes(ctx -> setSlots(ctx, IntegerArgumentType.getInteger(ctx, "maximo"), true))
						.then(Commands.argument("actual", IntegerArgumentType.integer())
							.executes(ctx -> setSlots(ctx, IntegerArgumentType.getInteger(ctx, "maximo"), false))))))
				.then(Commands.literal("restkit")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::giveRestKit)))
				.then(Commands.literal("advantage")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.then(Commands.argument("estado", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"normal", "ventaja", "desventaja"}, builder))
							.executes(SheetCommand::setAdvantage))))
				.then(Commands.literal("damagetype")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.then(Commands.argument("tipo", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(DAMAGE_TYPE_SUGGESTIONS, builder))
							.then(Commands.argument("afinidad", StringArgumentType.word())
								.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"normal", "resistant", "vulnerable", "immune"}, builder))
								.executes(SheetCommand::setDamageAffinity)))))
				.then(Commands.literal("gold")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.then(Commands.argument("modo", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"add", "set"}, builder))
							.then(Commands.argument("cantidad", IntegerArgumentType.integer())
								.executes(SheetCommand::setGold)))))
				.then(Commands.literal("passive")
					.then(Commands.argument("jugador", EntityArgument.player())
						.executes(SheetCommand::showPassivePerception)))
				.then(Commands.literal("turnitems")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::giveTurnItems)))
				.then(Commands.literal("rageitem")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::giveRageItem)))
				.then(Commands.literal("secondwinditem")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::giveSecondWindItem)))
				.then(Commands.literal("inspirationitem")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::giveInspirationItem)))
				.then(Commands.literal("wildshapeitem")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::giveWildShapeItem)))
				.then(Commands.literal("metamagicitem")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::giveMetamagicItem)))
				.then(Commands.literal("smiteitem")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::giveSmiteItem)))
				.then(Commands.literal("huntermarkitem")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::giveHunterMarkItem)))
				.then(Commands.literal("shielditem")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::giveShieldItem)))
				.then(Commands.literal("counterspellitem")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::giveCounterspellItem)))
				.then(Commands.literal("pact")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.then(Commands.argument("pacto", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"cadena", "hoja", "vara"}, builder))
							.executes(SheetCommand::setPact))))
				.then(Commands.literal("levelup")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.executes(SheetCommand::levelUp)))
				.then(Commands.literal("setlevel")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.then(Commands.argument("nivel", IntegerArgumentType.integer(1, 20))
							.executes(SheetCommand::setLevel))))
				.then(Commands.literal("setac")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.then(Commands.argument("valor", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"auto"}, builder))
							.executes(SheetCommand::setAc))))
				.then(Commands.literal("setroll")
					.then(Commands.argument("jugadores", EntityArgument.players())
						.then(Commands.argument("categoria", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"checks", "saves", "skills"}, builder))
							.then(Commands.argument("nombre", StringArgumentType.string())
								.suggests(SheetCommand::suggestRollNames)
								.then(Commands.argument("expresion", StringArgumentType.greedyString())
									.executes(SheetCommand::setRoll)))))));
	}

	//Ahora que checks/saves/skills son de solo-operador (ver network.SheetServerMessage), esta es la vía de
	//un DM/OP para seguir ajustándolas a distancia sin necesitar abrir la hoja de OTRO jugador como si fuera
	//la propia (cambio de arquitectura mayor, deliberadamente fuera de esta pasada — ver AUDIT.md). "nombre"
	//acepta el nombre en inglés que ya usa RollIndex.getBasicContext (p.ej. "Persuasion Check") o, para quien
	//prefiera no acordarse del nombre exacto, directamente el índice numérico.
	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestRollNames(
			CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		RollIndex.Category category = parseRollCategory(StringArgumentType.getString(ctx, "categoria"));
		if (category == null) return builder.buildFuture();
		return net.minecraft.commands.SharedSuggestionProvider.suggest(RollIndex.basicNames(category), builder);
	}

	private static RollIndex.Category parseRollCategory(String word) {
		return switch (word.toLowerCase(Locale.ROOT)) {
			case "checks" -> RollIndex.Category.CHECKS;
			case "saves" -> RollIndex.Category.SAVES;
			case "skills" -> RollIndex.Category.SKILLS;
			default -> null;
		};
	}

	private static int indexForRollName(RollIndex.Category category, String nombre) {
		try {
			int parsed = Integer.parseInt(nombre);
			if (parsed >= 0 && parsed < RollIndex.basicNames(category).size()) return parsed;
		} catch (NumberFormatException ignored) {
			//No era un índice numérico: se sigue probando por nombre debajo.
		}
		List<String> names = RollIndex.basicNames(category);
		for (int i = 0; i < names.size(); i++) {
			if (names.get(i).equalsIgnoreCase(nombre)) return i;
		}
		return -1;
	}

	private static int setRoll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		RollIndex.Category category = parseRollCategory(StringArgumentType.getString(ctx, "categoria"));
		if (category == null) {
			ctx.getSource().sendFailure(Component.literal("Categoría desconocida: usa checks, saves o skills."));
			return 0;
		}

		String nombre = StringArgumentType.getString(ctx, "nombre");
		int index = indexForRollName(category, nombre);
		if (index < 0) {
			ctx.getSource().sendFailure(Component.literal("No encuentro \"" + nombre + "\" en " + StringArgumentType.getString(ctx, "categoria") + "."));
			return 0;
		}

		String expresion = StringArgumentType.getString(ctx, "expresion");
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
			if (sheet == null) continue;
			SheetLoader.validateSheet(sheet);
			new RollIndex(category, index).saveInSheet(sheet, expresion);
			sendSheetUpdate(target, sheet);
		}
		ctx.getSource().sendSuccess(() -> Component.literal(nombre + " actualizado para " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//CA es un valor CALCULADO (10 + mod. Destreza + armadura real, ver CombatManager.armorClassOf) — no
	//había ninguna forma de fijarlo a mano para un caso especial (un objeto mágico, una regla de mesa
	//puntual) sin mentirle a Minecraft sobre la armadura real equipada. "auto" quita el override y vuelve
	//al cálculo normal.
	private static int setAc(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String valor = StringArgumentType.getString(ctx, "valor");
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
			if (sheet == null) continue;
			if ("auto".equalsIgnoreCase(valor)) {
				sheet.remove("armorClassOverride");
			} else {
				try {
					sheet.addProperty("armorClassOverride", Integer.parseInt(valor));
				} catch (NumberFormatException e) {
					ctx.getSource().sendFailure(Component.literal("\"" + valor + "\" no es un número válido ni \"auto\"."));
					continue;
				}
			}
			sendSheetUpdate(target, sheet);
		}
		ctx.getSource().sendSuccess(() -> Component.literal("CA actualizada para " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Un solo lugar donde entregar UN ítem "botón" a cada jugador de "jugadores" — antes cada
	//give*Item repetía este mismo cuerpo de 5 líneas, variando solo el builder del ítem y el mensaje.
	private static int giveItemToTargets(CommandContext<CommandSourceStack> ctx, Supplier<ItemStack> stackSupplier, String givenLabel) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			target.getInventory().add(stackSupplier.get());
		}
		ctx.getSource().sendSuccess(() -> Component.literal(givenLabel + " a " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	private static int giveSmiteItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, PaladinSmiteManager::buildDivineSmiteStack, "Castigo Divino entregado");
	}

	private static int giveHunterMarkItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, RangerHunterMarkManager::buildHunterMarkStack, "Marca del Cazador entregada");
	}

	private static int giveShieldItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, ShieldManager::buildShieldStack, "Escudo entregado");
	}

	private static int giveCounterspellItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, CounterspellManager::buildCounterspellStack, "Contrahechizo entregado");
	}

	//Pacto del brujo (Cadena/Hoja/Vara): elección permanente de subclase, al estilo de un preset — se
	//escribe en la hoja y ahí se queda. Único gancho mecánico real que encaja sin inventar un subsistema
	//nuevo: Pacto de la Hoja cambia la característica de ataque con arma a Carisma (ver
	//CombatManager.resolveWeapon). Cadena (familiar) y Vara (cantrips extra) se quedan como identidad
	//grabada en la hoja — este mod no modela familiares ni una lista de "hechizos conocidos" por
	//personaje, así que no hay dónde engancharlos sin inventar esos subsistemas para un solo pacto.
	private static int setPact(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String pacto = StringArgumentType.getString(ctx, "pacto").toLowerCase(java.util.Locale.ROOT);
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) applyPact(target, pacto);
		ctx.getSource().sendSuccess(() -> Component.literal("Pacto de " + pacto + " fijado para " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Público: también lo usa el Panel de DM (ver network.SheetAdjustMessage).
	public static void applyPact(ServerPlayer target, String pacto) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("warlockPact", pacto);
		sendSheetUpdate(target, sheet);
	}

	/**
	 * <p>Pone o quita una condición de 5e a un jugador. Público: lo usa el Panel de DM (ver
	 * {@code network.SheetAdjustMessage}). No pasa por {@code sendSheetUpdate}: la condición la persiste
	 * {@code Combatant.setConditionSources} por su cuenta, y avisar al propio afectado importa más que
	 * reenviarle la hoja entera — sin el aviso, quedarse paralizado parece que el juego se rompió.</p>
	 */
	public static void applyCondition(ServerPlayer target, String conditionLabel, boolean apply) {
		net.hawthorn.dndsheets.Condition condition = net.hawthorn.dndsheets.Condition.fromLabel(conditionLabel);
		if (condition == null) return;
		net.hawthorn.dndsheets.Combatant combatant = net.hawthorn.dndsheets.Combatant.of(target);
		if (combatant == null) return;
		if (apply) combatant.addCondition(condition);
		else combatant.removeCondition(condition);
		target.sendSystemMessage(Component.translatable(
			apply ? "chat.dndsheets.condition.gained" : "chat.dndsheets.condition.lost", condition.label())
			.withStyle(apply ? ChatFormatting.DARK_PURPLE : ChatFormatting.GRAY));
	}

	//Nivel de personaje, desacoplado del XP de Minecraft (ver SheetLoader.characterLevelOf, que ya leía
	//"characterLevel" de la hoja pero nunca tenía quién lo escribiera).
	private static int setLevel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		int nivel = IntegerArgumentType.getInteger(ctx, "nivel");
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) applyLevel(target, nivel);
		ctx.getSource().sendSuccess(() -> Component.literal("Nivel de personaje puesto a " + nivel + " para " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Subir UN nivel contándolo, frente a setlevel, que pone un número. Lo dispara el DM porque en una mesa
	//quien reparte los niveles es quien lleva la partida.
	private static int levelUp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) LevelUpManager.levelUp(target);
		ctx.getSource().sendSuccess(() -> Component.literal("Subido de nivel a " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Público: también lo usa el Panel de DM (ver network.SheetAdjustMessage).
	public static void applyLevel(ServerPlayer target, int nivel) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;
		//Las Mejoras de Puntuacion de Caracteristica se conceden AQUI, el unico punto por el que pasa un
		//cambio de nivel (comando y Panel de DM). Se cuentan por los niveles cruzados, asi que saltar del 1
		//al 8 concede las dos que tocan en vez de perder una — ver LevelUpManager.
		//El nivel EXPLÍCITO, por lo mismo que en LevelUpManager.levelUp: contar desde el nivel de XP le
		//quitaría al jugador las Mejoras de los niveles que el fallback se saltó de un brinco.
		LevelUpManager.grantImprovementsFor(sheet, SheetLoader.characterLevelOf(sheet), nivel);
		sheet.addProperty("characterLevel", nivel);
		//Sin esto, el PG máximo (que depende del nivel) se quedaba con el valor viejo hasta la próxima
		//reconexión — SheetLoader.applyClassHitPoints solo se llamaba antes en EntityJoinLevelEvent.
		SheetLoader.applyClassHitPoints(target, sheet);
		sendSheetUpdate(target, sheet);
	}

	private static int giveInspirationItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, BardInspirationManager::buildInspirationStack, "Cuerno de Inspiración entregado");
	}

	private static int giveWildShapeItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, DruidWildShapeManager::buildWildShapeStack, "Forma Salvaje entregada");
	}

	private static int giveMetamagicItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, SorcererMetamagicManager::buildTwinnedSpellStack, "Metamagia: Hechizo Gemelo entregada");
	}

	private static int giveRageItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, BarbarianRageManager::buildRageItemStack, "Tótem de Furia entregado");
	}

	private static int giveSecondWindItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, FighterSecondWindManager::buildSecondWindStack, "Segundo Aliento entregado");
	}

	private static int giveTurnItems(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			target.getInventory().add(TurnItemManager.buildNextTurnStack());
			target.getInventory().add(TurnItemManager.buildUndoTurnStack());
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Ítems de turno entregados a " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	private static int giveRestKit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, RestManager::buildRestKitStack, "Kit de Descanso entregado");
	}

	//Fija ventaja/desventaja para la SIGUIENTE tirada de ataque (arma o hechizo) de cada jugador; se
	//consume sola al resolverse esa tirada (ver CombatManager.consumeAdvantage).
	private static int setAdvantage(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String estado = StringArgumentType.getString(ctx, "estado");
		if (!"normal".equalsIgnoreCase(estado) && !"ventaja".equalsIgnoreCase(estado) && !"desventaja".equalsIgnoreCase(estado)) {
			ctx.getSource().sendFailure(Component.literal("Estado \"" + estado + "\" no reconocido. Usa: normal, ventaja o desventaja."));
			return 0;
		}
		String label = estado.toLowerCase(java.util.Locale.ROOT);

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) applyAdvantage(target, label);
		ctx.getSource().sendSuccess(() -> Component.literal("Próximo ataque en " + label + " para " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Público: también lo usa el Panel de DM (ver network.SheetAdjustMessage). "label" ya debe ser
	//"normal"/"ventaja"/"desventaja" — el llamador es quien decide con qué texto exacto llegar aquí.
	public static void applyAdvantage(ServerPlayer target, String label) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("nextAttackAdvantage", label);
		sendSheetUpdate(target, sheet);
	}

	//Fija resistencia/vulnerabilidad/inmunidad a un tipo de daño (p.ej. "fuego", "veneno") en la hoja;
	//"normal" borra la entrada (ver DamageTypes.multiplierFor, que lee esta misma "damageAffinities").
	private static int setDamageAffinity(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String tipo = StringArgumentType.getString(ctx, "tipo").toLowerCase(java.util.Locale.ROOT);
		String afinidad = StringArgumentType.getString(ctx, "afinidad").toLowerCase(java.util.Locale.ROOT);

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) applyDamageAffinity(target, tipo, afinidad);
		ctx.getSource().sendSuccess(() -> Component.literal("Afinidad a " + tipo + " puesta a " + afinidad + " para " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Público: también lo usa el Panel de DM (ver network.SheetAdjustMessage).
	public static void applyDamageAffinity(ServerPlayer target, String damageType, String affinity) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;

		JsonObject affinities = sheet.has("damageAffinities") ? sheet.getAsJsonObject("damageAffinities") : new JsonObject();
		if ("normal".equals(affinity)) {
			affinities.remove(damageType);
		} else {
			affinities.addProperty(damageType, affinity);
		}
		sheet.add("damageAffinities", affinities);
		sendSheetUpdate(target, sheet);
	}

	//Economía simple: un solo contador de "gold" por hoja (equivalente en piezas de oro). "add" suma
	//(puede ser negativo para gastar), "set" fija el valor directamente.
	private static int setGold(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String modo = StringArgumentType.getString(ctx, "modo");
		int cantidad = IntegerArgumentType.getInteger(ctx, "cantidad");

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) applyGold(target, modo, cantidad);
		ctx.getSource().sendSuccess(() -> Component.literal("Oro actualizado para " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Público: también lo usa el Panel de DM (ver network.SheetAdjustMessage). Devuelve el oro resultante,
	//para que el panel pueda refrescar lo que muestra sin pedirlo aparte.
	public static int applyGold(ServerPlayer target, String mode, int amount) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return 0;

		int current = sheet.has("gold") ? sheet.get("gold").getAsInt() : 0;
		//En long antes de volver a int: current + amount en int puro podía desbordar a negativo con
		//valores grandes (oro ya alto + un "add" grande), y el Math.max(0, ...) de después convertía ese
		//desborde en "vaciar el oro" en vez de sumar.
		int updated;
		if ("add".equals(mode)) {
			long sum = (long) current + (long) amount;
			updated = (int) Math.max(0, Math.min(sum, Integer.MAX_VALUE));
		} else {
			updated = Math.max(0, amount);
		}
		sheet.addProperty("gold", updated);
		sendSheetUpdate(target, sheet);
		return updated;
	}

	//Tirada secreta del DM: solo la ve quien ejecuta el comando (sendSuccess con allowLogging=false),
	//no se anuncia en el chat público.
	private static int showPassivePerception(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "jugador");
		int passive = passivePerceptionOf(target);
		String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(target.getStringUUID()), target);
		ctx.getSource().sendSuccess(() -> Component.literal("Percepción pasiva de " + name + ": " + passive), false);
		return passive;
	}

	//Público: también lo usa el Panel de DM (ver network.PassivePerceptionRequestMessage).
	public static int passivePerceptionOf(ServerPlayer target) {
		return PassiveScores.passivePerception(SheetLoader.getServerSheet(target.getStringUUID()));
	}

	private static int setSlots(CommandContext<CommandSourceStack> ctx, int max, boolean fillCurrent) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			int current = fillCurrent ? max : IntegerArgumentType.getInteger(ctx, "actual");
			applySlots(target, max, current);
		}

		ctx.getSource().sendSuccess(() -> Component.literal("Espacios de conjuro máximos puestos a " + max + " para " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Público: también lo usa el Panel de DM (ver network.SheetAdjustMessage). "current" se recorta a "max" igual que el comando.
	public static void applySlots(ServerPlayer target, int max, int current) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;

		//El comando ya acota [0,99] con Brigadier, pero el Panel de DM llega acá directo por
		//network.SheetAdjustMessage (un VarInt crudo, sin cota) — se acota acá, en el único punto por el
		//que pasan los dos caminos, para no depender de que cada llamador se acuerde de validar.
		max = Math.max(0, Math.min(max, 99));
		current = Math.max(0, Math.min(current, 99));

		SheetLoader.validateSheet(sheet);
		//Un comando de un solo número no puede decir de qué nivel son, así que van como espacios de nivel 1
		//—lo más conservador— y de paso quedan en la tabla: escribir solo el total dejaría al personaje sin
		//poder lanzar nada, porque lanzar mira la tabla.
		SpellSlots.setFlat(sheet, max, current);
		sendSheetUpdate(target, sheet);
	}

	//Sin el saveServer, un cambio de oro/nivel/espacios/etc. hecho por un DM (comando o Panel de DM) solo
	//tocaba la copia en memoria — sobrevivía a que el propio jugador reabriera su hoja (eso sí guarda,
	//ver network.SheetServerMessage) pero se perdía si el servidor se reiniciaba/caía antes del autoguardado
	//periódico de 5 min o de un /stop limpio. Todos los métodos de esta clase pasan por este único método
	//de salida, así que arreglarlo acá cierra el hueco para gold/level/slots/advantage/damageAffinity/pact
	//a la vez, sin tener que acordarse en cada uno.
	private static void sendSheetUpdate(ServerPlayer target, JsonObject sheet) {
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> target), new SheetClientMessage(sheet.toString().getBytes()));
		SheetLoader.saveServer(sheet, target.getStringUUID());
	}
}
