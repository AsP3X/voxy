package me.cortex.voxy.server.worldgen;

import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerTracker {
    private static final PlayerTracker INSTANCE = new PlayerTracker();
    private final Set<ServerPlayer> players;
    private final java.util.Map<java.util.UUID, it.unimi.dsi.fastutil.longs.LongSet> syncedChunks;
    
    private PlayerTracker() {
        this.players = ConcurrentHashMap.newKeySet();
        this.syncedChunks = new ConcurrentHashMap<>();
    }
    
    public static PlayerTracker getInstance() {
        return INSTANCE;
    }
    
    public void addPlayer(ServerPlayer player) {
        players.add(player);
        syncedChunks.put(player.getUUID(), it.unimi.dsi.fastutil.longs.LongSets.synchronize(new it.unimi.dsi.fastutil.longs.LongOpenHashSet()));
    }
    
    public void removePlayer(ServerPlayer player) {
        players.remove(player);
        syncedChunks.remove(player.getUUID());
    }
    
    public void clear() {
        players.clear();
        syncedChunks.clear();
    }
    
    public Collection<ServerPlayer> getPlayers() {
        return Collections.unmodifiableCollection(players);
    }

    public it.unimi.dsi.fastutil.longs.LongSet getSyncedChunks(java.util.UUID uuid) {
        return syncedChunks.get(uuid);
    }
    
    public int getPlayerCount() {
        return players.size();
    }
}
