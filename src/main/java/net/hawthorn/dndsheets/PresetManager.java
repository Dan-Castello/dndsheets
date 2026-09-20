package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * <p>Applies a {@link PresetRegistry.ClassPreset} to a player's actual sheet: fills in class, hit dice,
 * and ability scores, grants the starting weapon if the preset has one, and pushes the updated sheet to
 * the client. Used both by {@code /dndpresets apply} and by the picker on the sheet itself.</p>
 */
public class PresetManager {

	public static void applyPreset(ServerPlayer player, String presetId) {
		PresetRegistry.ClassPreset preset = PresetRegistry.get(presetId);
		if (preset == null) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;

		//If this SAME preset was already applied, the starting weapon and class resource item aren't
		//granted again: without this check, clicking "apply barbarian" repeatedly gave a new Rage totem
		//(and a new axe) every time. Switching to a DIFFERENT preset still grants both, as before.
		String previousPresetId = sheet.has("appliedPresetId") ? sheet.get("appliedPresetId").getAsString() : null;
		boolean samePresetAlreadyApplied = preset.id().equals(previousPresetId);

		//Switching to a DIFFERENT preset removes what the previous preset granted (starting weapon + class
		//resource item) before granting the new one — without this, switching presets several times piled
		//up a weapon and a resource item per switch instead of replacing them. Only items carrying the NBT
		//tag this same mod put there are touched (exact weaponId, or the class resource boolean flag): a
		//starting weapon that resolves to a plain vanilla item (untagged, see Config.buildWeaponStack)
		//can't be safely distinguished from one the player already had on their own, so that one is left
		//alone — better an occasional leftover than deleting something that wasn't from the preset.
		if (!samePresetAlreadyApplied && previousPresetId != null) {
			PresetRegistry.ClassPreset previous = PresetRegistry.get(previousPresetId);
			if (previous != null) {
				removeMatching(player, stack -> isTaggedWeapon(stack, previous.startingWeaponId()));
				removeMatching(player, stack -> isClassResourceItem(stack, previous.id()));
				removeMatching(player, stack -> isStartingGearItem(stack, previous.id()));
			}
		}

		SheetLoader.validateSheet(sheet);
		PresetRegistry.applyToSheet(sheet, preset);

		if (!samePresetAlreadyApplied) {
			if (preset.startingWeaponId() != null) {
				ItemStack weapon = Config.buildWeaponStack(preset.startingWeaponId(), 1);
				player.getInventory().add(weapon);
			}

			//Through the same path as the weapon: buildWeaponStack resolves a plain Minecraft item id
			//first, so chainmail needs nothing special and a mod id still works. It's marked with the same
			//criterion as the weapon and the resource item (our own NBT tag) so it CAN be removed when
			//switching to another preset — it used to carry no mark and stuck around forever, so trying
			//several presets kept piling up starting gear in the inventory.
			for (String gearId : preset.startingGear()) {
				ItemStack gear = Config.buildWeaponStack(gearId, 1);
				gear.getOrCreateTagElement("dndsheets").putString("startingGear", preset.id());
				player.getInventory().add(gear);
			}

			ItemStack resourceItem = classResourceItem(preset.id());
			if (resourceItem != null) player.getInventory().add(resourceItem);
		}

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		//No need to close and reopen anymore: an open sheet repaints itself once the full sheet arrives
		//(see CharacterSheetScreen.refreshIfOpen). The old notice asked for a step that no longer exists.
		player.sendSystemMessage(Component.translatable("chat.dndsheets.preset.applied", preset.name()).withStyle(ChatFeedback.RESOURCE));
	}

	//The preset only fills in the sheet (class/abilities/traits); without this, the item that activates
	//that class's resource (Rage, Second Wind...) never reached the player unless the DM remembered to
	//give it separately with /dndsheet. The preset ids
	//are the same English class names already used by test/dndsheets/presets/presets.json. Classes with
	//no entry here (wizard, warlock, cleric, rogue, monk) have no resource item: their signature feature
	//already arrives via TraitRegistry (rogue/monk) or is automatic without an item (wizard/warlock/cleric).
	private static ItemStack classResourceItem(String presetId) {
		return switch (presetId) {
			case "barbarian" -> BarbarianRageManager.buildRageItemStack();
			case "fighter" -> FighterSecondWindManager.buildSecondWindStack();
			case "bard" -> BardInspirationManager.buildInspirationStack();
			case "druid" -> DruidWildShapeManager.buildWildShapeStack();
			case "sorcerer" -> SorcererMetamagicManager.buildTwinnedSpellStack();
			case "paladin" -> PaladinSmiteManager.buildDivineSmiteStack();
			case "ranger" -> RangerHunterMarkManager.buildHunterMarkStack();
			default -> null;
		};
	}

	//Same boolean flags each manager already uses to mark its resource item — see classResourceItem.
	private static String resourceFlagFor(String presetId) {
		return switch (presetId) {
			case "barbarian" -> "rage";
			case "fighter" -> "secondWind";
			case "bard" -> "bardicInspiration";
			case "druid" -> "wildShape";
			case "sorcerer" -> "twinnedSpell";
			case "paladin" -> "divineSmite";
			case "ranger" -> "hunterMark";
			default -> null;
		};
	}

	private static boolean isTaggedWeapon(ItemStack stack, String weaponId) {
		if (weaponId == null || stack.isEmpty() || !stack.hasTag()) return false;
		CompoundTag dndTag = stack.getTag().getCompound("dndsheets");
		return dndTag.contains("weapon") && weaponId.equals(dndTag.getString("weapon"));
	}

	private static boolean isClassResourceItem(ItemStack stack, String presetId) {
		String flag = resourceFlagFor(presetId);
		if (flag == null || stack.isEmpty() || !stack.hasTag()) return false;
		return stack.getTag().getCompound("dndsheets").getBoolean(flag);
	}

	private static boolean isStartingGearItem(ItemStack stack, String presetId) {
		if (presetId == null || stack.isEmpty() || !stack.hasTag()) return false;
		CompoundTag dndTag = stack.getTag().getCompound("dndsheets");
		return dndTag.contains("startingGear") && presetId.equals(dndTag.getString("startingGear"));
	}

	private static void removeMatching(ServerPlayer player, Predicate<ItemStack> matches) {
		Container inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (matches.test(inventory.getItem(i))) inventory.setItem(i, ItemStack.EMPTY);
		}
	}

	public static List<String> presetIds() {
		return new ArrayList<>(PresetRegistry.ids());
	}

	public static List<String> presetNames(List<String> ids) {
		List<String> names = new ArrayList<>();
		for (String id : ids) {
			PresetRegistry.ClassPreset preset = PresetRegistry.get(id);
			names.add(preset != null ? preset.name() : id);
		}
		return names;
	}
}
