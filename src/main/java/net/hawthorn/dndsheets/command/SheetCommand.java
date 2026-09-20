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
 * <p>Sheet administration utilities that had no natural home in any other command.
 * {@code /dndsheet setslots} covers the most urgent gap found in the audit: before this,
 * {@code spellSlotsMax} was never written anywhere except its default of 0, so the Spellbook
 * was unusable without editing the sheet's JSON by hand on disk.</p>
 */
@Mod.EventBusSubscriber
public class SheetCommand {
	//Tab suggestions only (5e's 13 damage types): any other text is still valid, but for
	//DamageTypes.multiplierFor to actually match what weapons/spells say, it's best to always write the
	//same exact name — hence why it's worth suggesting. The list lives in DamageTypes, which is what
	//decides whether a resistance applies. Duplicating it here would risk suggesting the DM a type that
	//later wouldn't compare equal.
	private static final String[] DAMAGE_TYPE_SUGGESTIONS = net.hawthorn.dndsheets.DamageTypes.CANONICAL;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndsheet")
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(Commands.literal("setslots")
				.then(Commands.argument("players", EntityArgument.players())
					.then(Commands.argument("max", IntegerArgumentType.integer())
						.executes(ctx -> setSlots(ctx, IntegerArgumentType.getInteger(ctx, "max"), true))
						.then(Commands.argument("current", IntegerArgumentType.integer())
							.executes(ctx -> setSlots(ctx, IntegerArgumentType.getInteger(ctx, "max"), false))))))
				.then(Commands.literal("restkit")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::giveRestKit)))
				.then(Commands.literal("advantage")
					.then(Commands.argument("players", EntityArgument.players())
						.then(Commands.argument("state", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"normal", "advantage", "disadvantage"}, builder))
							.executes(SheetCommand::setAdvantage))))
				.then(Commands.literal("damagetype")
					.then(Commands.argument("players", EntityArgument.players())
						.then(Commands.argument("type", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(DAMAGE_TYPE_SUGGESTIONS, builder))
							.then(Commands.argument("affinity", StringArgumentType.word())
								.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"normal", "resistant", "vulnerable", "immune"}, builder))
								.executes(SheetCommand::setDamageAffinity)))))
				.then(Commands.literal("gold")
					.then(Commands.argument("players", EntityArgument.players())
						.then(Commands.argument("mode", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"add", "set"}, builder))
							.then(Commands.argument("amount", IntegerArgumentType.integer())
								.executes(SheetCommand::setGold)))))
				.then(Commands.literal("passive")
					.then(Commands.argument("player", EntityArgument.player())
						.executes(SheetCommand::showPassivePerception)))
				.then(Commands.literal("turnitems")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::giveTurnItems)))
				.then(Commands.literal("rageitem")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::giveRageItem)))
				.then(Commands.literal("secondwinditem")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::giveSecondWindItem)))
				.then(Commands.literal("inspirationitem")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::giveInspirationItem)))
				.then(Commands.literal("wildshapeitem")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::giveWildShapeItem)))
				.then(Commands.literal("metamagicitem")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::giveMetamagicItem)))
				.then(Commands.literal("smiteitem")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::giveSmiteItem)))
				.then(Commands.literal("huntermarkitem")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::giveHunterMarkItem)))
				.then(Commands.literal("shielditem")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::giveShieldItem)))
				.then(Commands.literal("counterspellitem")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::giveCounterspellItem)))
				.then(Commands.literal("pact")
					.then(Commands.argument("players", EntityArgument.players())
						.then(Commands.argument("pactType", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"chain", "blade", "tome"}, builder))
							.executes(SheetCommand::setPact))))
				.then(Commands.literal("levelup")
					.then(Commands.argument("players", EntityArgument.players())
						.executes(SheetCommand::levelUp)))
				//Multiclass: a level IN a class, not a bare level. Granted by the DM, like the rest of the
				//levels.
				.then(Commands.literal("multiclass")
					.then(Commands.argument("players", EntityArgument.players())
						.then(Commands.argument("class", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
								net.hawthorn.dndsheets.PresetRegistry.ids(), builder))
							.executes(SheetCommand::multiclass))))
				.then(Commands.literal("setlevel")
					.then(Commands.argument("players", EntityArgument.players())
						.then(Commands.argument("level", IntegerArgumentType.integer(1, 20))
							.executes(SheetCommand::setLevel))))
				.then(Commands.literal("setac")
					.then(Commands.argument("players", EntityArgument.players())
						.then(Commands.argument("value", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"auto"}, builder))
							.executes(SheetCommand::setAc))))
				.then(Commands.literal("setroll")
					.then(Commands.argument("players", EntityArgument.players())
						.then(Commands.argument("category", StringArgumentType.word())
							.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"checks", "saves", "skills"}, builder))
							.then(Commands.argument("name", StringArgumentType.string())
								.suggests(SheetCommand::suggestRollNames)
								.then(Commands.argument("rollExpression", StringArgumentType.greedyString())
									.executes(SheetCommand::setRoll)))))));
	}

	//Now that checks/saves/skills are operator-only (see network.SheetServerMessage), this is the way for
	//a DM/OP to keep adjusting them remotely without needing to open ANOTHER player's sheet as if it were
	//their own (a bigger architecture change, deliberately out of scope for this pass). "name"
	//accepts the English name RollIndex.getBasicContext already uses (e.g. "Persuasion Check") or, for
	//whoever prefers not to remember the exact name, the numeric index directly.
	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestRollNames(
			CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		RollIndex.Category category = parseRollCategory(StringArgumentType.getString(ctx, "category"));
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

	private static int indexForRollName(RollIndex.Category category, String name) {
		try {
			int parsed = Integer.parseInt(name);
			if (parsed >= 0 && parsed < RollIndex.basicNames(category).size()) return parsed;
		} catch (NumberFormatException ignored) {
			//Not a numeric index: fall through and keep trying by name below.
		}
		List<String> names = RollIndex.basicNames(category);
		for (int i = 0; i < names.size(); i++) {
			if (names.get(i).equalsIgnoreCase(name)) return i;
		}
		return -1;
	}

	private static int setRoll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		RollIndex.Category category = parseRollCategory(StringArgumentType.getString(ctx, "category"));
		if (category == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.roll.unknown_category"));
			return 0;
		}

		String name = StringArgumentType.getString(ctx, "name");
		int index = indexForRollName(category, name);
		if (index < 0) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.sheet.roll_not_found", name, StringArgumentType.getString(ctx, "category")));
			return 0;
		}

		String expresion = StringArgumentType.getString(ctx, "rollExpression");
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) {
			JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
			if (sheet == null) continue;
			SheetLoader.validateSheet(sheet);
			new RollIndex(category, index).saveInSheet(sheet, expresion);
			sendSheetUpdate(target, sheet);
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.sheet.roll_updated", name, targets.size()), true);
		return targets.size();
	}

	//AC is a CALCULATED value (10 + Dex mod + real armor, see CombatManager.armorClassOf) — there was no
	//way to fix it by hand for a special case (a magic item, a one-off table rule) without lying to
	//Minecraft about the real armor equipped. "auto" removes the override and goes back to the normal
	//calculation.
	private static int setAc(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String value = StringArgumentType.getString(ctx, "value");
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) {
			JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
			if (sheet == null) continue;
			if ("auto".equalsIgnoreCase(value)) {
				sheet.remove("armorClassOverride");
			} else {
				try {
					sheet.addProperty("armorClassOverride", Integer.parseInt(value));
				} catch (NumberFormatException e) {
					ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.sheet.invalid_ac_value", value));
					continue;
				}
			}
			sendSheetUpdate(target, sheet);
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.sheet.ac_updated", targets.size()), true);
		return targets.size();
	}

	//A single place to hand out ONE "button" item to each player in "players" — every give*Item used
	//to repeat this same 5-line body, varying only the item builder and the message.
	private static int giveItemToTargets(CommandContext<CommandSourceStack> ctx, Supplier<ItemStack> stackSupplier, String givenLabelKey) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) {
			target.getInventory().add(stackSupplier.get());
		}
		ctx.getSource().sendSuccess(() -> Component.translatable(givenLabelKey, targets.size()), true);
		return targets.size();
	}

	private static int giveSmiteItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, PaladinSmiteManager::buildDivineSmiteStack, "chat.dndsheets.sheet.smite_given");
	}

	private static int giveHunterMarkItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, RangerHunterMarkManager::buildHunterMarkStack, "chat.dndsheets.sheet.hunter_mark_given");
	}

	private static int giveShieldItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, ShieldManager::buildShieldStack, "chat.dndsheets.sheet.shield_given");
	}

	private static int giveCounterspellItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, CounterspellManager::buildCounterspellStack, "chat.dndsheets.sheet.counterspell_given");
	}

	//Warlock's Pact (Chain/Blade/Tome): a permanent subclass choice, preset-style — it gets written on
	//the sheet and stays there. The only real mechanical hook that fits without inventing a new
	//subsystem: Pact of the Blade switches the weapon attack ability to Charisma (see
	//CombatManager.resolveWeapon). Chain (familiar) and Tome (extra cantrips) remain as identity recorded
	//on the sheet — this mod doesn't model familiars or a per-character "known spells" list, so there's
	//nowhere to hook them without inventing those subsystems for a single pact.
	private static int setPact(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String pact = StringArgumentType.getString(ctx, "pactType").toLowerCase(java.util.Locale.ROOT);
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) applyPact(target, pact);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.sheet.pact_set", pact, targets.size()), true);
		return targets.size();
	}

	//Public: also used by the DM Panel (see network.SheetAdjustMessage).
	public static void applyPact(ServerPlayer target, String pact) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("warlockPact", pact);
		sendSheetUpdate(target, sheet);
	}

	/**
	 * <p>Applies or removes a 5e condition on a player. Public: used by the DM Panel (see
	 * {@code network.SheetAdjustMessage}). It doesn't go through {@code sendSheetUpdate}: the condition
	 * is persisted by {@code Combatant.setConditionSources} on its own, and notifying the affected player
	 * matters more than resending them the whole sheet — without the notice, being paralyzed looks like
	 * the game broke.</p>
	 */
	public static void applyCondition(ServerPlayer target, String conditionLabel, boolean apply) {
		net.hawthorn.dndsheets.Condition condition = net.hawthorn.dndsheets.Condition.fromLabel(conditionLabel);
		if (condition == null) return;
		net.hawthorn.dndsheets.Combatant combatant = net.hawthorn.dndsheets.Combatant.of(target);
		if (combatant == null) return;
		if (apply) combatant.addCondition(condition);
		else combatant.removeCondition(condition);
		target.sendSystemMessage(Component.translatable(
			apply ? "chat.dndsheets.condition.gained" : "chat.dndsheets.condition.lost", condition.displayLabel())
			.withStyle(apply ? ChatFormatting.DARK_PURPLE : ChatFormatting.GRAY));
	}

	//Character level, decoupled from Minecraft XP (see SheetLoader.characterLevelOf, which already read
	//"characterLevel" from the sheet but never had anything writing it).
	private static int setLevel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		int level = IntegerArgumentType.getInteger(ctx, "level");
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) applyLevel(target, level);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.sheet.level_set", level, targets.size()), true);
		return targets.size();
	}

	//Gaining ONE level by incrementing, as opposed to setlevel, which sets a number. Triggered by the DM
	//because at a table it's whoever runs the game who hands out levels.
	private static int levelUp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) LevelUpManager.levelUp(target);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.sheet.leveled_up", targets.size()), true);
		return targets.size();
	}

	//Public: also used by the DM Panel (see network.SheetAdjustMessage).
	public static void applyLevel(ServerPlayer target, int level) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;
		//Ability Score Improvements are granted HERE, the only point every level change (command and DM
		//Panel) passes through. They're counted by the levels crossed, so jumping from 1 to 8 grants the
		//two that are due instead of losing one — see LevelUpManager.
		//The EXPLICIT level, for the same reason as in LevelUpManager.levelUp: counting from the XP level
		//would strip the player of the Improvements for the levels the fallback skipped over in one jump.
		LevelUpManager.grantImprovementsFor(sheet, SheetLoader.characterLevelOf(sheet), level);
		sheet.addProperty("characterLevel", level);
		//Without this, max HP (which depends on level) would keep its old value until the next
		//reconnect — SheetLoader.applyClassHitPoints used to only be called in EntityJoinLevelEvent.
		SheetLoader.applyClassHitPoints(target, sheet);
		sendSheetUpdate(target, sheet);
	}

	/**
	 * <p>Gains a level <b>in a specific class</b>. It's the same as {@code levelup} except for the one
	 * thing multiclassing changes: which class the gained level belongs to.</p>
	 *
	 * <p>It goes through {@code applyLevel} like everything else, so the Ability Score Improvement is
	 * granted through the same place and at the same total levels — a fighter 3 / wizard 1 gets it upon
	 * reaching 4, which is what 5e says. Writing the level through another path would have been the way
	 * to lose it.</p>
	 */
	private static int multiclass(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String classId = StringArgumentType.getString(ctx, "class");
		if (net.hawthorn.dndsheets.PresetRegistry.get(classId) == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.sheet.no_such_class", classId));
			return 0;
		}

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) applyMulticlass(target, classId);

		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.sheet.class_level_granted", classId, targets.size()), true);
		return targets.size();
	}

	/**
	 * <p>Gains a level <b>in a specific class</b> for ONE player. It's the same as {@code applyLevel}
	 * except for the one thing multiclassing changes: which class the gained level belongs to.</p>
	 *
	 * <p>It goes through {@link #applyLevel} like everything else, so the Ability Score Improvement is
	 * granted through the same place and at the same total levels — a fighter 3 / wizard 1 gets it upon
	 * reaching 4, which is what 5e says. Writing the level through another path would have been the way
	 * to lose it.</p>
	 *
	 * <p>Public: also used by {@code network.MulticlassMessage} (the sheet's own "Multiclass" button).
	 * Returns {@code false} without touching anything if {@code classId} doesn't exist in {@code
	 * PresetRegistry} or the player has no sheet — the caller decides what to do with that.</p>
	 */
	public static boolean applyMulticlass(ServerPlayer target, String classId) {
		if (net.hawthorn.dndsheets.PresetRegistry.get(classId) == null) return false;
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return false;

		String currentClassId = sheet.has("appliedPresetId") ? sheet.get("appliedPresetId").getAsString() : "";
		java.util.Map<String, Integer> levels = net.hawthorn.dndsheets.ClassLevels.addLevel(
			sheet, classId, currentClassId, SheetLoader.characterLevelOf(sheet));

		//applyLevel writes the total level and grants whatever's due; the class split is already on the
		//sheet, so HP and slots recalculate themselves by reading it.
		applyLevel(target, net.hawthorn.dndsheets.ClassLevels.total(levels));
		target.sendSystemMessage(Component.translatable("chat.dndsheets.character.now_you_are", net.hawthorn.dndsheets.ClassLevels.describe(levels)).withStyle(net.minecraft.ChatFormatting.GREEN));
		return true;
	}

	private static int giveInspirationItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, BardInspirationManager::buildInspirationStack, "chat.dndsheets.sheet.inspiration_given");
	}

	private static int giveWildShapeItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, DruidWildShapeManager::buildWildShapeStack, "chat.dndsheets.sheet.wildshape_given");
	}

	private static int giveMetamagicItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, SorcererMetamagicManager::buildTwinnedSpellStack, "chat.dndsheets.sheet.metamagic_given");
	}

	private static int giveRageItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, BarbarianRageManager::buildRageItemStack, "chat.dndsheets.sheet.rage_given");
	}

	private static int giveSecondWindItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, FighterSecondWindManager::buildSecondWindStack, "chat.dndsheets.sheet.second_wind_given");
	}

	private static int giveTurnItems(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) {
			target.getInventory().add(TurnItemManager.buildNextTurnStack());
			target.getInventory().add(TurnItemManager.buildUndoTurnStack());
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.sheet.turn_items_given", targets.size()), true);
		return targets.size();
	}

	private static int giveRestKit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return giveItemToTargets(ctx, RestManager::buildRestKitStack, "chat.dndsheets.sheet.rest_kit_given");
	}

	//Sets advantage/disadvantage for the NEXT attack roll (weapon or spell) of each player; it consumes
	//itself once that roll resolves (see CombatManager.consumeAdvantage).
	private static int setAdvantage(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String state = StringArgumentType.getString(ctx, "state");
		if (!"normal".equalsIgnoreCase(state) && !"advantage".equalsIgnoreCase(state) && !"disadvantage".equalsIgnoreCase(state)) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.sheet.unknown_advantage_state", state));
			return 0;
		}
		String label = state.toLowerCase(java.util.Locale.ROOT);

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) applyAdvantage(target, label);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.sheet.next_attack_state", label, targets.size()), true);
		return targets.size();
	}

	//Public: also used by the DM Panel (see network.SheetAdjustMessage). "label" must already be
	//"normal"/"advantage"/"disadvantage" — the caller is the one who decides what exact text arrives here.
	public static void applyAdvantage(ServerPlayer target, String label) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("nextAttackAdvantage", label);
		sendSheetUpdate(target, sheet);
	}

	//Sets resistance/vulnerability/immunity to a damage type (e.g. "fire", "poison") on the sheet;
	//"normal" removes the entry (see DamageTypes.multiplierFor, which reads this same "damageAffinities").
	private static int setDamageAffinity(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String damageType = net.hawthorn.dndsheets.DamageTypes.normalize(StringArgumentType.getString(ctx, "type"));
		String affinity = StringArgumentType.getString(ctx, "affinity").toLowerCase(java.util.Locale.ROOT);

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) applyDamageAffinity(target, damageType, affinity);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.sheet.damage_affinity_set", damageType, affinity, targets.size()), true);
		return targets.size();
	}

	//Public: also used by the DM Panel (see network.SheetAdjustMessage).
	public static void applyDamageAffinity(ServerPlayer target, String damageType, String affinity) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;

		JsonObject affinities = sheet.has("damageAffinities") ? sheet.getAsJsonObject("damageAffinities") : new JsonObject();
		//A key saved under an old Spanish spelling ("fuego") is the same type as "fire": drop it so the two never coexist.
		affinities.keySet().removeIf(written -> net.hawthorn.dndsheets.DamageTypes.normalize(written).equals(damageType));
		if ("normal".equals(affinity)) {
			affinities.remove(damageType);
		} else {
			affinities.addProperty(damageType, affinity);
		}
		sheet.add("damageAffinities", affinities);
		sendSheetUpdate(target, sheet);
	}

	//Simple economy: a single "gold" counter per sheet (in gold-piece equivalent). "add" adds
	//(can be negative to spend), "set" sets the value directly.
	private static int setGold(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String mode = StringArgumentType.getString(ctx, "mode");
		int amount = IntegerArgumentType.getInteger(ctx, "amount");

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) applyGold(target, mode, amount);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.sheet.gold_updated", targets.size()), true);
		return targets.size();
	}

	//Public: also used by the DM Panel (see network.SheetAdjustMessage). Returns the resulting gold, so
	//the panel can refresh what it shows without requesting it separately.
	public static int applyGold(ServerPlayer target, String mode, int amount) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return 0;

		int current = sheet.has("gold") ? sheet.get("gold").getAsInt() : 0;
		//As a long before going back to int: current + amount in plain int could overflow into negative
		//with large values (already-high gold + a big "add"), and the Math.max(0, ...) that follows would
		//turn that overflow into "wipe the gold out" instead of adding.
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

	//Secret DM roll: only seen by whoever ran the command (sendSuccess with allowLogging=false),
	//not announced in public chat.
	private static int showPassivePerception(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		int passive = passivePerceptionOf(target);
		String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(target.getStringUUID()), target);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.character.passive_perception", name, passive), false);
		return passive;
	}

	//Public: also used by the DM Panel (see network.PassivePerceptionRequestMessage).
	public static int passivePerceptionOf(ServerPlayer target) {
		return PassiveScores.passivePerception(SheetLoader.getServerSheet(target.getStringUUID()));
	}

	private static int setSlots(CommandContext<CommandSourceStack> ctx, int max, boolean fillCurrent) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) {
			int current = fillCurrent ? max : IntegerArgumentType.getInteger(ctx, "current");
			applySlots(target, max, current);
		}

		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.sheet.spell_slots_set", max, targets.size()), true);
		return targets.size();
	}

	//Public: also used by the DM Panel (see network.SheetAdjustMessage). "current" is clamped to "max" just like the command.
	public static void applySlots(ServerPlayer target, int max, int current) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;

		//The command already bounds [0,99] via Brigadier, but the DM Panel arrives here directly through
		//network.SheetAdjustMessage (a raw VarInt, no bound) — it's bounded here, the single point both
		//paths pass through, so as not to depend on every caller remembering to validate.
		max = Math.max(0, Math.min(max, 99));
		current = Math.max(0, Math.min(current, 99));

		SheetLoader.validateSheet(sheet);
		//A single-number command can't say which level they're for, so they go in as level-1 slots
		//—the most conservative choice— and end up in the table along the way: writing only the total
		//would leave the character unable to cast anything, because casting looks at the table.
		SpellSlots.setFlat(sheet, max, current);
		sendSheetUpdate(target, sheet);
	}

	//Without saveServer, a gold/level/slots/etc. change made by a DM (command or DM Panel) only touched
	//the in-memory copy — it survived the player reopening their own sheet (that does save, see
	//network.SheetServerMessage) but was lost if the server restarted/crashed before the periodic 5-min
	//autosave or a clean /stop. Every method in this class routes through this single exit point, so
	//fixing it here closes the gap for gold/level/slots/advantage/damageAffinity/pact all at once, without
	//having to remember it in each one.
	private static void sendSheetUpdate(ServerPlayer target, JsonObject sheet) {
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> target), new SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		SheetLoader.saveServer(sheet, target.getStringUUID());
	}
}
