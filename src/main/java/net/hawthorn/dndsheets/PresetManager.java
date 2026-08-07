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
 * <p>Aplica un {@link PresetRegistry.ClassPreset} a la hoja real de un jugador: rellena clase, dado de
 * golpe y características, entrega el arma inicial si el preset tiene una, y empuja la hoja actualizada
 * al cliente. Usado tanto por {@code /dndpresets apply} como por el selector en la propia hoja.</p>
 */
public class PresetManager {

	public static void applyPreset(ServerPlayer player, String presetId) {
		PresetRegistry.ClassPreset preset = PresetRegistry.get(presetId);
		if (preset == null) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;

		//Si ya tenía ESTE MISMO preset aplicado, no se le vuelve a dar el arma inicial ni el ítem de
		//recurso de clase: sin este chequeo, pulsar "aplicar bárbaro" repetidas veces regalaba un Tótem de
		//Furia (y un hacha) nuevo cada vez. Cambiar a un preset DISTINTO sigue entregando ambos, como antes.
		String previousPresetId = sheet.has("appliedPresetId") ? sheet.get("appliedPresetId").getAsString() : null;
		boolean samePresetAlreadyApplied = preset.id().equals(previousPresetId);

		//Cambiar a un preset DISTINTO retira lo que entregó el preset anterior (arma inicial + ítem de
		//recurso de clase) antes de entregar lo nuevo — sin esto, cambiar de preset varias veces acumulaba
		//un arma y un ítem de recurso por cada cambio en vez de reemplazarlos. Solo se toca lo que lleva la
		//etiqueta NBT que puso este mismo mod (weaponId exacto, o el flag booleano del recurso de clase):
		//un arma inicial que resuelva a un ítem vanilla puro (sin etiqueta, ver Config.buildWeaponStack)
		//no se puede distinguir de forma segura de una que el jugador ya tuviera por su cuenta, así que esa
		//no se toca — mejor dejar un extra ocasional que borrar algo que no era del preset.
		if (!samePresetAlreadyApplied && previousPresetId != null) {
			PresetRegistry.ClassPreset previous = PresetRegistry.get(previousPresetId);
			if (previous != null) {
				removeMatching(player, stack -> isTaggedWeapon(stack, previous.startingWeaponId()));
				removeMatching(player, stack -> isClassResourceItem(stack, previous.id()));
			}
		}

		SheetLoader.validateSheet(sheet);
		PresetRegistry.applyToSheet(sheet, preset);

		if (!samePresetAlreadyApplied) {
			if (preset.startingWeaponId() != null) {
				ItemStack weapon = Config.buildWeaponStack(preset.startingWeaponId(), 1);
				player.getInventory().add(weapon);
			}

			ItemStack resourceItem = classResourceItem(preset.id());
			if (resourceItem != null) player.getInventory().add(resourceItem);
		}

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(sheet.toString().getBytes()));
		player.sendSystemMessage(Component.literal("Preset \"" + preset.name() + "\" aplicado. Cierra y vuelve a abrir tu hoja para verlo.").withStyle(ChatFeedback.RESOURCE));
	}

	//El preset solo rellena la hoja (clase/características/rasgos); sin esto, el ítem que activa el
	//recurso de esa clase (Furia, Segundo Aliento...) nunca llegaba al jugador salvo que el DM se
	//acordara de dárselo aparte con /dndsheet — ver AUDIT_UX.md, sección Jugador #1. Los ids de preset
	//son los mismos nombres de clase en inglés que ya usa test/dndsheets/presets/presets.json. Las clases
	//sin entrada acá (mago, brujo, clérigo, pícaro, monje) no tienen ítem de recurso: su rasgo icónico ya
	//llega por TraitRegistry (pícaro/monje) o es automático sin ítem (mago/brujo/clérigo).
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

	//Mismos flags booleanos que ya usa cada manager para marcar su ítem de recurso — ver classResourceItem.
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
