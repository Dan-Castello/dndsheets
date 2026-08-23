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
 * <p>Elegir un bloque de CUALQUIER mod instalado — sin esto, un mueble de un mod de decoración no
 * tendría forma de entrar al editor de trazado ({@code DungeonTraceScreen}). Lista
 * {@link ForgeRegistries#BLOCKS}, el registro global donde vive todo bloque de todo mod cargado, así
 * que no hace falta compatibilidad especial por mod: un mueble ajeno es solo una fila más.</p>
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
