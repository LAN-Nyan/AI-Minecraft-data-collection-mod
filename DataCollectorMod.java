// File: src/main/java/com/precognition/datacollector/DataCollectorMod.java
package com.precognition.datacollector;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

@Mod("precognition_datacollector")
public class DataCollectorMod {
    
    private static final String DATA_DIR = "precognition_data";
    private static final String ACTION_SEQUENCE_FILE = DATA_DIR + "/action_sequences.jsonl";
    private static final int TICK_INTERVAL = 2; // Collect every 2 ticks (10Hz)
    private static final int SEQUENCE_WINDOW = 100; // Track last 100 actions per player
    
    private final Minecraft mc;
    private final Map<UUID, PlayerSequence> playerSequences;
    private final List<String> pendingWrites;
    private int tickCounter = 0;
    private String sessionId;
    
    public DataCollectorMod() {
        this.mc = Minecraft.getInstance();
        this.playerSequences = new HashMap<>();
        this.pendingWrites = new ArrayList<>();
        this.sessionId = "session_" + Instant.now().getEpochSecond();
        
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
        MinecraftForge.EVENT_BUS.register(this);
        
        initializeDataCollection();
    }
    
    private void clientSetup(final FMLClientSetupEvent event) {
        System.out.println("====================================");
        System.out.println("Precognition Data Collector v1.0");
        System.out.println("Session: " + sessionId);
        System.out.println("Output: " + ACTION_SEQUENCE_FILE);
        System.out.println("Format: Action1 -> Action2 sequences");
        System.out.println("====================================");
    }
    
    private void initializeDataCollection() {
        try {
            Path dataPath = Paths.get(DATA_DIR);
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to initialize: " + e.getMessage());
        }
    }
    
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.player == null || mc.level == null || mc.isPaused()) return;
        
        tickCounter++;
        
        if (tickCounter % TICK_INTERVAL == 0) {
            collectPlayerActions();
        }
        
        if (pendingWrites.size() >= 50) {
            flushData();
        }
    }
    
    @SubscribeEvent
    public void onChatMessage(ClientChatReceivedEvent event) {
        if (mc.player == null) return;
        
        String fullMessage = event.getMessage().getString();
        String senderName = extractSenderName(fullMessage);
        if (senderName == null) return;
        
        String content = extractMessageContent(fullMessage, senderName);
        Player sender = findPlayerByName(senderName);
        
        ChatAction chatAction = new ChatAction();
        chatAction.timestamp = Instant.now().toEpochMilli();
        chatAction.tick = tickCounter;
        chatAction.senderName = senderName;
        chatAction.message = content;
        
        if (sender != null) {
            chatAction.senderUUID = sender.getUUID().toString();
            chatAction.position = capturePosition(sender);
            
            PlayerSequence seq = playerSequences.get(sender.getUUID());
            if (seq != null) {
                seq.addChatAction(chatAction);
            }
        }
    }
    
    private void collectPlayerActions() {
        for (Player player : mc.level.players()) {
            UUID uuid = player.getUUID();
            PlayerSequence sequence = playerSequences.computeIfAbsent(uuid, k -> new PlayerSequence(uuid, player.getName().getString()));
            
            PlayerAction action = capturePlayerAction(player);
            sequence.addAction(action);
            
            // When we have enough data, write sequences
            if (sequence.shouldWriteSequence()) {
                writeActionSequence(sequence);
            }
        }
        
        // Clean up offline players
        playerSequences.entrySet().removeIf(entry -> 
            mc.level.players().stream().noneMatch(p -> p.getUUID().equals(entry.getKey()))
        );
    }
    
    private PlayerAction capturePlayerAction(Player player) {
        PlayerAction action = new PlayerAction();
        action.timestamp = Instant.now().toEpochMilli();
        action.tick = tickCounter;
        action.position = capturePosition(player);
        action.rotation = captureRotation(player);
        action.velocity = captureVelocity(player);
        action.state = captureState(player);
        action.environment = captureEnvironment(player);
        action.combat = captureCombat(player);
        return action;
    }
    
    private Position capturePosition(Player player) {
        Vec3 pos = player.position();
        Position p = new Position();
        p.x = pos.x;
        p.y = pos.y;
        p.z = pos.z;
        return p;
    }
    
    private Rotation captureRotation(Player player) {
        Rotation r = new Rotation();
        r.yaw = player.getYRot();
        r.pitch = player.getXRot();
        return r;
    }
    
    private Velocity captureVelocity(Player player) {
        Vec3 vel = player.getDeltaMovement();
        Velocity v = new Velocity();
        v.x = vel.x;
        v.y = vel.y;
        v.z = vel.z;
        v.speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        return v;
    }
    
    private State captureState(Player player) {
        State s = new State();
        s.health = player.getHealth();
        s.hunger = player.getFoodData().getFoodLevel();
        s.sprinting = player.isSprinting();
        s.sneaking = player.isShiftKeyDown();
        s.flying = player.getAbilities().flying;
        s.onGround = player.onGround();
        s.inWater = player.isInWater();
        s.heldItem = player.getMainHandItem().getItem().toString().replace("minecraft:", "");
        s.swinging = player.swinging;
        return s;
    }
    
    private Environment captureEnvironment(Player player) {
        Environment e = new Environment();
        e.biome = mc.level.getBiome(player.blockPosition()).toString().replace("minecraft:", "");
        e.lightLevel = mc.level.getMaxLocalRawBrightness(player.blockPosition());
        e.dimension = mc.level.dimension().location().toString().replace("minecraft:", "");
        e.blockBelow = mc.level.getBlockState(player.blockPosition().below()).getBlock().getName().getString().replace("minecraft:", "");
        return e;
    }
    
    private Combat captureCombat(Player player) {
        Combat c = new Combat();
        c.nearbyPlayers = (int) mc.level.players().stream()
            .filter(p -> !p.equals(player) && p.distanceTo(player) < 50.0)
            .count();
        
        if (c.nearbyPlayers > 0) {
            Player nearest = mc.level.players().stream()
                .filter(p -> !p.equals(player))
                .min(Comparator.comparingDouble(p -> p.distanceTo(player)))
                .orElse(null);
            
            if (nearest != null) {
                c.nearestPlayerDistance = nearest.distanceTo(player);
                Vec3 toNearest = nearest.position().subtract(player.position()).normalize();
                double angle = Math.toDegrees(Math.atan2(toNearest.z, toNearest.x)) - 90;
                c.nearestPlayerAngleDiff = Math.abs(normalizeAngle(angle - player.getYRot()));
            }
        }
        
        c.inCombat = player.hurtTime > 0;
        c.timeSinceDamage = player.hurtTime;
        return c;
    }
    
    private void writeActionSequence(PlayerSequence sequence) {
        List<PlayerAction> actions = sequence.getRecentActions();
        if (actions.size() < 2) return;
        
        // Create Action1 -> Action2 pairs
        for (int i = 0; i < actions.size() - 1; i++) {
            PlayerAction current = actions.get(i);
            PlayerAction next = actions.get(i + 1);
            
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"session_id\":\"").append(sessionId).append("\",");
            json.append("\"player_uuid\":\"").append(sequence.playerUUID).append("\",");
            json.append("\"player_name\":\"").append(escape(sequence.playerName)).append("\",");
            
            // Current action (Action1)
            json.append("\"action1\":{");
            json.append("\"timestamp\":").append(current.timestamp).append(",");
            json.append("\"tick\":").append(current.tick).append(",");
            appendActionData(json, current);
            json.append("},");
            
            // Next action (Action2)
            json.append("\"action2\":{");
            json.append("\"timestamp\":").append(next.timestamp).append(",");
            json.append("\"tick\":").append(next.tick).append(",");
            appendActionData(json, next);
            json.append("},");
            
            // Time delta
            json.append("\"time_delta_ms\":").append(next.timestamp - current.timestamp).append(",");
            json.append("\"tick_delta\":").append(next.tick - current.tick);
            
            // Recent chat context (if any)
            List<ChatAction> recentChats = sequence.getRecentChats(current.timestamp, 5000);
            if (!recentChats.isEmpty()) {
                json.append(",\"recent_chat\":[");
                for (int j = 0; j < recentChats.size(); j++) {
                    if (j > 0) json.append(",");
                    ChatAction chat = recentChats.get(j);
                    json.append("{");
                    json.append("\"message\":\"").append(escape(chat.message)).append("\",");
                    json.append("\"time_before_action\":").append(current.timestamp - chat.timestamp);
                    json.append("}");
                }
                json.append("]");
            }
            
            json.append("}\n");
            pendingWrites.add(json.toString());
        }
    }
    
    private void appendActionData(StringBuilder json, PlayerAction action) {
        json.append("\"position\":{")
            .append("\"x\":").append(String.format("%.3f", action.position.x)).append(",")
            .append("\"y\":").append(String.format("%.3f", action.position.y)).append(",")
            .append("\"z\":").append(String.format("%.3f", action.position.z))
            .append("},");
        
        json.append("\"rotation\":{")
            .append("\"yaw\":").append(String.format("%.2f", action.rotation.yaw)).append(",")
            .append("\"pitch\":").append(String.format("%.2f", action.rotation.pitch))
            .append("},");
        
        json.append("\"velocity\":{")
            .append("\"x\":").append(String.format("%.6f", action.velocity.x)).append(",")
            .append("\"y\":").append(String.format("%.6f", action.velocity.y)).append(",")
            .append("\"z\":").append(String.format("%.6f", action.velocity.z)).append(",")
            .append("\"speed\":").append(String.format("%.6f", action.velocity.speed))
            .append("},");
        
        json.append("\"state\":{")
            .append("\"health\":").append(action.state.health).append(",")
            .append("\"hunger\":").append(action.state.hunger).append(",")
            .append("\"sprinting\":").append(action.state.sprinting).append(",")
            .append("\"sneaking\":").append(action.state.sneaking).append(",")
            .append("\"flying\":").append(action.state.flying).append(",")
            .append("\"on_ground\":").append(action.state.onGround).append(",")
            .append("\"in_water\":").append(action.state.inWater).append(",")
            .append("\"held_item\":\"").append(escape(action.state.heldItem)).append("\",")
            .append("\"swinging\":").append(action.state.swinging)
            .append("},");
        
        json.append("\"environment\":{")
            .append("\"biome\":\"").append(escape(action.environment.biome)).append("\",")
            .append("\"light_level\":").append(action.environment.lightLevel).append(",")
            .append("\"dimension\":\"").append(escape(action.environment.dimension)).append("\",")
            .append("\"block_below\":\"").append(escape(action.environment.blockBelow)).append("\"")
            .append("},");
        
        json.append("\"combat\":{")
            .append("\"nearby_players\":").append(action.combat.nearbyPlayers).append(",")
            .append("\"nearest_distance\":").append(String.format("%.2f", action.combat.nearestPlayerDistance)).append(",")
            .append("\"nearest_angle_diff\":").append(String.format("%.2f", action.combat.nearestPlayerAngleDiff)).append(",")
            .append("\"in_combat\":").append(action.combat.inCombat).append(",")
            .append("\"time_since_damage\":").append(action.combat.timeSinceDamage)
            .append("}");
    }
    
    private void flushData() {
        if (pendingWrites.isEmpty()) return;
        
        try (FileWriter writer = new FileWriter(ACTION_SEQUENCE_FILE, true)) {
            for (String line : pendingWrites) {
                writer.write(line);
            }
            System.out.println("Wrote " + pendingWrites.size() + " action sequences");
            pendingWrites.clear();
        } catch (IOException e) {
            System.err.println("Failed to write data: " + e.getMessage());
        }
    }
    
    private String extractSenderName(String message) {
        if (message.startsWith("<") && message.contains(">")) {
            return message.substring(1, message.indexOf(">")).trim();
        }
        if (message.contains(":")) {
            String[] parts = message.split(":", 2);
            String potential = parts[0].replaceAll("\\[.*?\\]", "").trim();
            if (!potential.isEmpty() && potential.length() < 16) {
                return potential;
            }
        }
        return null;
    }
    
    private String extractMessageContent(String fullMessage, String senderName) {
        String content = fullMessage;
        if (content.startsWith("<" + senderName + ">")) {
            content = content.substring(("<" + senderName + ">").length()).trim();
        } else if (content.contains(senderName + ":")) {
            int colonIndex = content.indexOf(senderName + ":");
            content = content.substring(colonIndex + (senderName + ":").length()).trim();
        }
        return content;
    }
    
    private Player findPlayerByName(String name) {
        if (mc.level == null) return null;
        return mc.level.players().stream()
            .filter(p -> p.getName().getString().equalsIgnoreCase(name))
            .findFirst().orElse(null);
    }
    
    private double normalizeAngle(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
    
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
    
    // Data classes
    private static class PlayerSequence {
        UUID playerUUID;
        String playerName;
        List<PlayerAction> actions;
        List<ChatAction> chats;
        int writeCounter = 0;
        
        PlayerSequence(UUID uuid, String name) {
            this.playerUUID = uuid;
            this.playerName = name;
            this.actions = new ArrayList<>();
            this.chats = new ArrayList<>();
        }
        
        void addAction(PlayerAction action) {
            actions.add(action);
            if (actions.size() > SEQUENCE_WINDOW) {
                actions.remove(0);
            }
        }
        
        void addChatAction(ChatAction chat) {
            chats.add(chat);
            if (chats.size() > 20) {
                chats.remove(0);
            }
        }
        
        boolean shouldWriteSequence() {
            writeCounter++;
            return writeCounter >= 50 && actions.size() >= 2;
        }
        
        List<PlayerAction> getRecentActions() {
            if (writeCounter >= 50) {
                writeCounter = 0;
                return new ArrayList<>(actions);
            }
            return new ArrayList<>();
        }
        
        List<ChatAction> getRecentChats(long actionTimestamp, long windowMs) {
            return chats.stream()
                .filter(c -> actionTimestamp - c.timestamp <= windowMs && c.timestamp <= actionTimestamp)
                .toList();
        }
    }
    
    private static class PlayerAction {
        long timestamp;
        int tick;
        Position position;
        Rotation rotation;
        Velocity velocity;
        State state;
        Environment environment;
        Combat combat;
    }
    
    private static class ChatAction {
        long timestamp;
        int tick;
        String senderName;
        String senderUUID;
        String message;
        Position position;
    }
    
    private static class Position {
        double x, y, z;
    }
    
    private static class Rotation {
        float yaw, pitch;
    }
    
    private static class Velocity {
        double x, y, z, speed;
    }
    
    private static class State {
        float health;
        int hunger;
        boolean sprinting, sneaking, flying, onGround, inWater, swinging;
        String heldItem;
    }
    
    private static class Environment {
        String biome, dimension, blockBelow;
        int lightLevel;
    }
    
    private static class Combat {
        int nearbyPlayers;
        double nearestPlayerDistance = -1;
        double nearestPlayerAngleDiff = -1;
        boolean inCombat;
        int timeSinceDamage;
    }
}
