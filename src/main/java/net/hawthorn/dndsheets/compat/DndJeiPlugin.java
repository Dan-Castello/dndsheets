package net.hawthorn.dndsheets.compat;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.client.gui.ContentFormScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * <p><b>Optional</b> JEI integration: on the content forms, JEI's item list shows up beside the form and an
 * item can be dragged from it onto any item field (the same drop the mod's own icon picker fills). JEI finds
 * this class by its annotation; without JEI it never loads, same as {@link JadePlugin}.</p>
 */
@JeiPlugin
public class DndJeiPlugin implements IModPlugin {
	@Override
	public ResourceLocation getPluginUid() {
		return new ResourceLocation(DndsheetsMod.MODID, "jei");
	}

	@Override
	public void registerGuiHandlers(IGuiHandlerRegistration registration) {
		registration.addGuiScreenHandler(ContentFormScreen.class, screen -> {
			Rect2i area = screen.panelArea();
			return new IGuiProperties() {
				public Class<? extends net.minecraft.client.gui.screens.Screen> getScreenClass() { return ContentFormScreen.class; }
				public int getGuiLeft() { return area.getX(); }
				public int getGuiTop() { return area.getY(); }
				public int getGuiXSize() { return area.getWidth(); }
				public int getGuiYSize() { return area.getHeight(); }
				public int getScreenWidth() { return screen.width; }
				public int getScreenHeight() { return screen.height; }
			};
		});
		registration.addGhostIngredientHandler(ContentFormScreen.class, new IGhostIngredientHandler<ContentFormScreen>() {
			@Override
			public <I> List<Target<I>> getTargetsTyped(ContentFormScreen gui, ITypedIngredient<I> ingredient, boolean doStart) {
				List<Target<I>> targets = new ArrayList<>();
				if (ingredient.getIngredient(VanillaTypes.ITEM_STACK).isEmpty()) return targets;
				for (net.minecraft.client.gui.components.EditBox box : gui.itemBoxes()) {
					targets.add(new Target<I>() {
						public Rect2i getArea() { return new Rect2i(box.getX(), box.getY(), box.getWidth(), box.getHeight()); }
						public void accept(I dropped) {
							if (dropped instanceof net.minecraft.world.item.ItemStack stack) {
								box.setValue(ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
							}
						}
					});
				}
				return targets;
			}

			@Override
			public void onComplete() {
			}
		});
	}
}
