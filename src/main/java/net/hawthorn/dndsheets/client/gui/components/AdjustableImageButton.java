package net.hawthorn.dndsheets.client.gui.components;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class AdjustableImageButton extends Button {
    protected ResourceLocation resourceLocation;
    protected int xTexStart;
    protected int yTexStart;
    protected int yDiffTex;
    protected int textureWidth;
    protected int textureHeight;
    public int txtColor = 0xF4F3F3;
    /**
     * <p>Label shadow. Kept separate from {@link #txtColor} because the two decisions aren't the same: Minecraft's
     * shadow is a copy of the text one pixel down and to the right, in the color darkened to a
     * quarter. With light text on a dark background that reads as relief and helps legibility; with dark text
     * on parchment (the selected tab) the copy ends up as dark as the original and the word
     * reads as if written twice.</p>
     *
     * <p>Starts as true because that's what {@code renderString} did, which this replaces.</p>
     */
    public boolean txtShadow = true;

    public AdjustableImageButton(int pX, int pY, int pWidth, int pHeight, int pXTexStart, int pYTexStart, ResourceLocation pResourceLocation, Button.OnPress pOnPress) {
        this(pX, pY, pWidth, pHeight, pXTexStart, pYTexStart, pHeight, pResourceLocation, 256, 256, pOnPress);
    }

    public AdjustableImageButton(int pX, int pY, int pWidth, int pHeight, int pXTexStart, int pYTexStart, int pYDiffTex, ResourceLocation pResourceLocation, Button.OnPress pOnPress) {
        this(pX, pY, pWidth, pHeight, pXTexStart, pYTexStart, pYDiffTex, pResourceLocation, 256, 256, pOnPress);
    }

    public AdjustableImageButton(int pX, int pY, int pWidth, int pHeight, int pXTexStart, int pYTexStart, int pYDiffTex, ResourceLocation pResourceLocation, int pTextureWidth, int pTextureHeight, Button.OnPress pOnPress) {
        this(pX, pY, pWidth, pHeight, pXTexStart, pYTexStart, pYDiffTex, pResourceLocation, pTextureWidth, pTextureHeight, pOnPress, CommonComponents.EMPTY);
    }

    public AdjustableImageButton(int pX, int pY, int pWidth, int pHeight, int pXTexStart, int pYTexStart, int pYDiffTex, ResourceLocation pResourceLocation, int pTextureWidth, int pTextureHeight, Button.OnPress pOnPress, Component pMessage) {
        super(pX, pY, pWidth, pHeight, pMessage, pOnPress, DEFAULT_NARRATION);
        this.textureWidth = pTextureWidth;
        this.textureHeight = pTextureHeight;
        this.xTexStart = pXTexStart;
        this.yTexStart = pYTexStart;
        this.yDiffTex = pYDiffTex;
        this.resourceLocation = pResourceLocation;
    }

    public void setImage(ResourceLocation pResourceLocation, int pXTexStart, int pYTexStart, int pYDiffTex, int pTextureWidth, int pTextureHeight) {
        this.resourceLocation = pResourceLocation;
        this.xTexStart = pXTexStart;
        this.yTexStart = pYTexStart;
        this.yDiffTex = pYDiffTex;
        this.textureWidth = pTextureWidth;
        this.textureHeight = pTextureHeight;
    }

    public void renderWidget(GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        this.renderTexture(pGuiGraphics, this.resourceLocation, this.getX(), this.getY(), this.xTexStart, this.yTexStart, this.yDiffTex, this.width, this.height, this.textureWidth, this.textureHeight);
        //Direct drawing instead of renderString(): that ends up in drawCenteredString, which forces the shadow
        //without letting it be turned off. See txtShadow.
        int color = txtColor | Mth.ceil(this.alpha * 255.0F) << 24;
        pGuiGraphics.drawString(minecraft.font, this.getMessage(),
            this.getX() + (this.width - minecraft.font.width(this.getMessage())) / 2,
            this.getY() + (this.height - 8) / 2, color, txtShadow);
    }
}