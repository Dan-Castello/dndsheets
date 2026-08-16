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
     * <p>Sombra del rótulo. Va aparte de {@link #txtColor} porque las dos decisiones no son la misma: la
     * sombra de Minecraft es una copia del texto un píxel abajo y a la derecha, en el color oscurecido a
     * la cuarta parte. Con texto claro sobre fondo oscuro eso es relieve y ayuda a leer; con texto oscuro
     * sobre pergamino (la pestaña seleccionada) la copia queda tan oscura como el original y la palabra
     * se lee escrita dos veces.</p>
     *
     * <p>Arranca en true porque es lo que hacía {@code renderString}, que es a lo que sustituye.</p>
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
        //Dibujo directo en vez de renderString(): ese acaba en drawCenteredString, que fuerza la sombra sin
        //dejar apagarla. Ver txtShadow.
        int color = txtColor | Mth.ceil(this.alpha * 255.0F) << 24;
        pGuiGraphics.drawString(minecraft.font, this.getMessage(),
            this.getX() + (this.width - minecraft.font.width(this.getMessage())) / 2,
            this.getY() + (this.height - 8) / 2, color, txtShadow);
    }
}