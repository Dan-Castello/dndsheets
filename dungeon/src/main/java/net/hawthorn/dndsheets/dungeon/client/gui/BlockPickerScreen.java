package net.hawthorn.dndsheets.dungeon.client.gui;
import net.hawthorn.dndsheets.client.gui.ListPickerScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * <p>Pick a block from ANY installed mod — without this, furniture from a decoration mod would have
 * no way to enter the trace editor ({@code DungeonTraceScreen}). Lists
 * {@link ForgeRegistries#BLOCKS}, the global registry where every block from every loaded mod lives,
 * so no special per-mod compatibility is needed: furniture from another mod is just one more row.</p>
 */
public class BlockPickerScreen extends ListPickerScreen {
	private final Consumer<ResourceLocation> onPick;

	private BlockPickerScreen(Screen parent, Consumer<ResourceLocation> onPick) {
		super(Component.translatable("gui.dndsheets.block_picker.title"), parent);
		this.onPick = onPick;
	}

	public static void open(Screen parent, Consumer<ResourceLocation> onPick) {
		Minecraft.getInstance().setScreen(new BlockPickerScreen(parent, onPick));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		List<ResourceLocation> ids = ForgeRegistries.BLOCKS.getKeys().stream()
			.sorted(java.util.Comparator.comparing(ResourceLocation::toString)).collect(Collectors.toList());
		for (ResourceLocation id : ids) {
			addRow(Component.literal(id.toString()), b -> {
				onPick.accept(id);
				this.onClose();
			});
		}
	}
}
