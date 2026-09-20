package net.hawthorn.dndsheets.compat;

import net.hawthorn.dndsheets.Combatant;
import net.hawthorn.dndsheets.Condition;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

import java.util.StringJoiner;

/**
 * <p><b>Optional</b> Jade integration: pointing at a creature shows its AC, HP and conditions
 * on the HUD, without opening anything. It answers the audit's diagnosis —the engine knows far more than
 * the player ever gets to see— without inventing an overlay of its own that would compete with the one the
 * player already has placed and configured.</p>
 *
 * <p><b>Why this is ONE class and {@code CuriosCompat} is two.</b> There the isolation has to be
 * built because we are the ones calling Curios, and merely naming one of its types is enough
 * for the JVM to try to resolve it on an install without Curios. Here the direction is the opposite: nobody
 * in this mod references this class. Whoever loads it is Jade, scanning the {@link WailaPlugin} annotation
 * on its own startup — if Jade isn't installed, nobody loads it, and no
 * {@code NoClassDefFoundError} is possible. The isolation comes from the shape of the problem, not from a guard.</p>
 *
 * <p><b>It calculates nothing.</b> Everything comes from {@link Combatant}, the same object the combat engine uses
 * to resolve an attack; if {@code Combatant.of} returns {@code null} —a mob without a monster block,
 * a player without a sheet— not a single line is written here and Jade draws its usual tooltip. That is
 * invariant 9 (leave alone whatever isn't configured) applied to the HUD.</p>
 */
@WailaPlugin
public class JadePlugin implements IWailaPlugin {

	@Override
	public void register(IWailaCommonRegistration registration) {
		//Entity.class and not LivingEntity: an NPC with a sheet can have any registered entity
		//as its body (see SheetLoader.spawnNpc), and Combatant.of already filters out whatever doesn't play by the rules.
		registration.registerEntityDataProvider(StatBlock.INSTANCE, Entity.class);
	}

	@Override
	public void registerClient(IWailaClientRegistration registration) {
		registration.registerEntityComponent(StatBlock.INSTANCE, Entity.class);
	}

	/**
	 * <p>The two halves of the same datum. They go together on purpose: AC and conditions live on the sheet
	 * or in the monster's NBT, that is <b>only on the server</b> —the client doesn't have them and can't
	 * calculate them—, so they must be sent explicitly. HP is sent too even though
	 * {@code getHealth()} is in fact synced: this mod's HP is the monster block's, scaled
	 * by difficulty ({@code Config.scaleMonsterMaxHp}), and it needn't match the HP of the
	 * vanilla entity acting as the body.</p>
	 */
	enum StatBlock implements IEntityComponentProvider, IServerDataProvider<EntityAccessor> {
		INSTANCE;

		private static final ResourceLocation UID = new ResourceLocation(DndsheetsMod.MODID, "stat_block");

		private static final String AC = "ac";
		private static final String HP = "hp";
		private static final String MAX_HP = "maxHp";
		private static final String TEMP_HP = "tempHp";
		private static final String CONDITIONS = "conditions";

		@Override
		public ResourceLocation getUid() {
			return UID;
		}

		@Override
		public void appendServerData(CompoundTag data, EntityAccessor accessor) {
			Combatant combatant = Combatant.of(accessor.getEntity());
			if (combatant == null) return; //Invariant 9: with no sheet or block, Jade carries on as if this mod did not exist.

			data.putInt(AC, combatant.armorClass());
			data.putInt(HP, combatant.currentHp());
			data.putInt(MAX_HP, combatant.maxHp());
			data.putInt(TEMP_HP, combatant.temporaryHp());

			//The same lowercase labels the sheet and ConditionListScreen already show — this mod doesn't
			//translate conditions by language key anywhere, and the HUD isn't going to be the first.
			StringJoiner labels = new StringJoiner(", ");
			for (Condition condition : combatant.conditions()) labels.add(condition.displayLabel());
			if (labels.length() > 0) data.putString(CONDITIONS, labels.toString());
		}

		@Override
		public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
			CompoundTag data = accessor.getServerData();
			if (!data.contains(AC)) return; //Not a combatant of this mod, or the server did not send it.

			//The sheet's labels ("AC"/"HP") are reused instead of opening new keys: they are the
			//same two words, and a new key means 8 language files (see sync_lang_variants.py).
			tooltip.add(Component.translatable("gui.dndsheets.character_sheet.label_armor_class_ac")
				.append(" " + data.getInt(AC))
				.withStyle(ChatFormatting.GRAY));

			int temporary = data.getInt(TEMP_HP);
			tooltip.add(Component.translatable("gui.dndsheets.character_sheet.label_hit_points")
				.append(" " + data.getInt(HP) + "/" + data.getInt(MAX_HP) + (temporary > 0 ? " (+" + temporary + ")" : ""))
				.withStyle(ChatFormatting.GRAY));

			if (data.contains(CONDITIONS)) {
				tooltip.add(Component.literal(data.getString(CONDITIONS)).withStyle(ChatFormatting.RED));
			}
		}
	}
}
