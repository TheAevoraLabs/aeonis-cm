package com.qc.aeonis.neoforge.llm.screen;

import com.qc.aeonis.llm.network.payloads.LlmConfigSubmitPayload;
import com.qc.aeonis.llm.network.payloads.LlmOpenGuiPayload;
import com.qc.aeonis.llm.network.payloads.LlmSpawnBotPayload;
import com.qc.aeonis.llm.network.payloads.LlmTestConnectionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.util.ArrayList;
import java.util.List;

public class AeonisLlmConfigScreen extends Screen {
    private final LlmOpenGuiPayload initialData;
    
    private String provider;
    private String model;
    private String apiKey = "";
    private double temperature;
    private int maxTokens;
    private int maxBlocksPerMinute;
    private int maxEditRadius;
    private boolean griefProtection;
    
    private List<Button> providerButtons = new ArrayList<>();
    private CycleButton<String> modelSelector;
    private EditBox apiKeyField;
    private String statusMessage = "";
    private boolean statusSuccess = true;
    private long statusMessageTime = 0;

    public AeonisLlmConfigScreen(LlmOpenGuiPayload data) {
        super(Component.literal("Aeonis LLM Configuration"));
        this.initialData = data;
        this.provider = data.currentProvider();
        this.model = data.currentModel();
        this.temperature = data.temperature();
        this.maxTokens = data.maxTokens();
        this.maxBlocksPerMinute = data.maxBlocksPerMinute();
        this.maxEditRadius = data.maxEditRadius();
        this.griefProtection = data.griefProtection();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Provider Selection
        int pY = 45;
        addProviderButton("OpenAI", centerX - 100, pY);
        addProviderButton("Anthropic", centerX - 30, pY);
        addProviderButton("Google", centerX + 40, pY);

        // Model Selection
        modelSelector = CycleButton.builder(Component::literal, model)
            .withValues(getModelsForProvider(provider))
            .create(centerX - 100, 85, 200, 20, Component.literal("Model"), (button, value) -> {
                this.model = value;
            });
        addRenderableWidget(modelSelector);

        // API Key
        apiKeyField = new EditBox(this.font, centerX - 100, 135, 200, 20, Component.literal("API Key"));
        apiKeyField.setMaxLength(256);
        if (initialData.hasApiKey()) {
            apiKeyField.setSuggestion("******** (Hidden)");
        }
        addRenderableWidget(apiKeyField);

        // Action Buttons
        addRenderableWidget(Button.builder(Component.literal("Test Connection"), b -> testConnection())
            .bounds(centerX - 105, 170, 100, 20).build());
        
        addRenderableWidget(Button.builder(Component.literal("Save Config"), b -> saveConfig())
            .bounds(centerX + 5, 170, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Spawn Aeonis"), b -> spawnAeonis())
            .bounds(centerX - 105, 195, 100, 20).build());
            
        addRenderableWidget(Button.builder(Component.literal("Despawn"), b -> despawnAeonis())
            .bounds(centerX + 5, 195, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(centerX - 50, 230, 100, 20).build());
            
        updateButtonStates();
    }

    private void addProviderButton(String name, int x, int y) {
        Button b = Button.builder(Component.literal(name), button -> {
            this.provider = name.toUpperCase();
            this.model = getModelsForProvider(this.provider).get(0);
            updateButtonStates();
            refreshModelSelector();
        }).bounds(x, y, 65, 20).build();
        providerButtons.add(b);
        addRenderableWidget(b);
    }

    private void updateButtonStates() {
        for (Button b : providerButtons) {
            b.active = !b.getMessage().getString().equalsIgnoreCase(provider);
        }
    }

    private void refreshModelSelector() {
        // Simple way to refresh: just remove and re-add with new values
        removeWidget(modelSelector);
        modelSelector = CycleButton.builder(Component::literal, getModelsForProvider(provider).get(0))
            .withValues(getModelsForProvider(provider))
            .create(this.width / 2 - 100, 85, 200, 20, Component.literal("Model"), (button, value) -> {
                this.model = value;
            });
        addRenderableWidget(modelSelector);
    }

    private List<String> getModelsForProvider(String provider) {
        if (provider.equalsIgnoreCase("OPENAI")) return List.of("gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo");
        if (provider.equalsIgnoreCase("ANTHROPIC")) return List.of("claude-3-opus-20240229", "claude-3-sonnet-20240229", "claude-3-haiku-20240307");
        if (provider.equalsIgnoreCase("GOOGLE")) return List.of("gemini-1.5-pro", "gemini-1.5-flash", "gemini-1.0-pro");
        return List.of("default");
    }

    private void testConnection() {
        String key = apiKeyField.getValue();
        sendPacket(new LlmTestConnectionPayload(provider, model, key.isEmpty() ? null : key, temperature));
        statusMessage = "Testing connection...";
        statusSuccess = true;
        statusMessageTime = System.currentTimeMillis();
    }

    private void saveConfig() {
        String key = apiKeyField.getValue();
        sendPacket(new LlmConfigSubmitPayload(
            provider, model, key, temperature, maxTokens, maxBlocksPerMinute, maxEditRadius, griefProtection
        ));
        statusMessage = "Saving configuration...";
        statusSuccess = true;
        statusMessageTime = System.currentTimeMillis();
    }

    private void spawnAeonis() {
        sendPacket(new LlmSpawnBotPayload(true));
    }

    private void despawnAeonis() {
        sendPacket(new LlmSpawnBotPayload(false));
    }

    private void sendPacket(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (Minecraft.getInstance().getConnection() != null) {
            Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
        }
    }

    public void handleStatusUpdate(String message, boolean success) {
        this.statusMessage = message;
        this.statusSuccess = success;
        this.statusMessageTime = System.currentTimeMillis();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        
        int centerX = this.width / 2;
        graphics.text(this.font, "Provider:", centerX - 100, 33, 0xAAAAAA);
        graphics.text(this.font, "Model:", centerX - 140, 75, 0xAAAAAA);
        graphics.text(this.font, "API Key:", centerX - 140, 123, 0xAAAAAA);
        
        if (!statusMessage.isEmpty() && System.currentTimeMillis() - statusMessageTime < 5000) {
            int statusColor = statusSuccess ? 0x55FF55 : 0xFF5555;
            graphics.centeredText(this.font, statusMessage, centerX, this.height - 30, statusColor);
        }
    }
    
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
