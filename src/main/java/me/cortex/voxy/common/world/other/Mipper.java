package me.cortex.voxy.common.world.other;

import net.minecraft.world.level.block.LiquidBlock;

import static me.cortex.voxy.common.world.other.Mapper.withLight;

//Mipper for data
public class Mipper {
    //TODO: compute the opacity of the block then mip w.r.t those blocks
    // as distant horizons done


    //TODO: also pass in the level its mipping from, cause at lower levels you want to preserve block details
    // but at higher details you want more air



    //TODO: instead of opacity only, add a level to see if the visual bounding box allows for seeing through top down etc
    private static final int[] CUBE_INDEX_TO_Y = new int[]{0, 0, 0, 0, 1, 1, 1, 1};

    private static boolean hasFluid(long state, Mapper mapper) {
        return !mapper.getBlockStateFromBlockId(Mapper.getBlockId(state)).getFluidState().isEmpty();
    }

    private static boolean isPureFluid(long state, Mapper mapper) {
        return mapper.getBlockStateFromBlockId(Mapper.getBlockId(state)).getBlock() instanceof LiquidBlock;
    }

    private static boolean isFullOpaque(long state, Mapper mapper) {
        return mapper.getBlockStateOpacity(state) >= 15;
    }

    private static int pickRepresentative(long[] states, int blockId, int preferredY, Mapper mapper, boolean requireFluid) {
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < states.length; i++) {
            long state = states[i];
            if (Mapper.isAir(state) || Mapper.getBlockId(state) != blockId) {
                continue;
            }
            if (requireFluid && !hasFluid(state, mapper)) {
                continue;
            }
            int score = 0;
            score += CUBE_INDEX_TO_Y[i] == preferredY ? 128 : 0;
            score += isPureFluid(state, mapper) ? 32 : 0;
            score += Mapper.getLightId(state);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int chooseVisibleFluid(long[] states, Mapper mapper) {
        int fluidLayer = -1;
        for (int y = 1; y >= 0 && fluidLayer == -1; y--) {
            for (int i = 0; i < states.length; i++) {
                if (CUBE_INDEX_TO_Y[i] != y || Mapper.isAir(states[i])) {
                    continue;
                }
                if (hasFluid(states[i], mapper)) {
                    fluidLayer = y;
                    break;
                }
            }
        }
        if (fluidLayer == -1) {
            return -1;
        }

        for (int i = 0; i < states.length; i++) {
            if (CUBE_INDEX_TO_Y[i] > fluidLayer && !Mapper.isAir(states[i]) && isFullOpaque(states[i], mapper)) {
                return -1;
            }
        }

        int bestFluidBlockId = -1;
        int bestFluidScore = Integer.MIN_VALUE;
        int opaqueScore = 0;
        for (int i = 0; i < states.length; i++) {
            long state = states[i];
            if (Mapper.isAir(state) || CUBE_INDEX_TO_Y[i] != fluidLayer) {
                continue;
            }
            if (hasFluid(state, mapper)) {
                int blockId = Mapper.getBlockId(state);
                int score = 0;
                for (int j = 0; j < states.length; j++) {
                    long other = states[j];
                    if (Mapper.isAir(other) || CUBE_INDEX_TO_Y[j] != fluidLayer || Mapper.getBlockId(other) != blockId || !hasFluid(other, mapper)) {
                        continue;
                    }
                    score += isPureFluid(other, mapper) ? 8 : 6;
                }
                if (score > bestFluidScore) {
                    bestFluidScore = score;
                    bestFluidBlockId = blockId;
                }
            } else if (isFullOpaque(state, mapper)) {
                opaqueScore += 6;
            }
        }

        if (bestFluidBlockId == -1 || opaqueScore > bestFluidScore) {
            return -1;
        }
        return pickFluidRepresentative(states, bestFluidBlockId, fluidLayer, mapper);
    }

    /**
     * When merging 2×2×2 fluid cells for LOD, prefer the biome that appears most often among
     * fluid samples at the visible layer, then apply the same light / pure-fluid scoring as
     * pickRepresentative. Without this, a single corner cell's biome can win and
     * water tint jumps at mip boundaries (visible as zig-zag lines near biome transitions).
     */
    private static int pickFluidRepresentative(long[] states, int blockId, int fluidLayer, Mapper mapper) {
        int[] distinct = new int[8];
        int[] counts = new int[8];
        int n = 0;
        for (int i = 0; i < states.length; i++) {
            if (CUBE_INDEX_TO_Y[i] != fluidLayer) {
                continue;
            }
            long state = states[i];
            if (Mapper.isAir(state) || Mapper.getBlockId(state) != blockId || !hasFluid(state, mapper)) {
                continue;
            }
            int biome = Mapper.getBiomeId(state);
            int k;
            for (k = 0; k < n; k++) {
                if (distinct[k] == biome) {
                    counts[k]++;
                    break;
                }
            }
            if (k == n) {
                distinct[n] = biome;
                counts[n] = 1;
                n++;
            }
        }
        if (n == 0) {
            return pickRepresentative(states, blockId, fluidLayer, mapper, true);
        }
        int majorityBiome = distinct[0];
        int majorityCount = counts[0];
        for (int k = 1; k < n; k++) {
            if (counts[k] > majorityCount) {
                majorityCount = counts[k];
                majorityBiome = distinct[k];
            } else if (counts[k] == majorityCount && distinct[k] < majorityBiome) {
                majorityBiome = distinct[k];
            }
        }

        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < states.length; i++) {
            if (CUBE_INDEX_TO_Y[i] != fluidLayer) {
                continue;
            }
            long state = states[i];
            if (Mapper.isAir(state) || Mapper.getBlockId(state) != blockId || !hasFluid(state, mapper)) {
                continue;
            }
            if (Mapper.getBiomeId(state) != majorityBiome) {
                continue;
            }
            int score = 128;//same weight as preferred-Y match in pickRepresentative
            score += isPureFluid(state, mapper) ? 32 : 0;
            score += Mapper.getLightId(state);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        if (bestIndex == -1) {
            return pickRepresentative(states, blockId, fluidLayer, mapper, true);
        }
        return bestIndex;
    }

    private static int chooseDominantState(long[] states, Mapper mapper) {
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < states.length; i++) {
            long state = states[i];
            if (Mapper.isAir(state)) {
                continue;
            }
            int blockId = Mapper.getBlockId(state);
            int count = 0;
            int highestY = 0;
            for (int j = 0; j < states.length; j++) {
                long other = states[j];
                if (!Mapper.isAir(other) && Mapper.getBlockId(other) == blockId) {
                    count++;
                    highestY = Math.max(highestY, CUBE_INDEX_TO_Y[j]);
                }
            }

            int score = 0;
            score += count << 8;
            score += mapper.getBlockStateOpacity(blockId) << 3;
            score += highestY << 2;
            score += hasFluid(state, mapper) ? 2 : 0;
            score += isPureFluid(state, mapper) ? 1 : 0;

            if (score > bestScore) {
                bestScore = score;
                bestIndex = pickRepresentative(states, blockId, highestY, mapper, false);
            }
        }
        return bestIndex;
    }

    public static long mip(long I000, long I100, long I001, long I101,
                           long I010, long I110, long I011, long I111,
                          Mapper mapper) {
        long[] states = new long[]{I000, I100, I001, I101, I010, I110, I011, I111};

        int visibleFluid = chooseVisibleFluid(states, mapper);
        if (visibleFluid != -1) {
            return states[visibleFluid];
        }

        int dominantState = chooseDominantState(states, mapper);
        if (dominantState != -1) {
            return states[dominantState];
        } else {
            // Light byte is (block<<4)|sky (see DHImporter / Mapper). Old code summed (id&0xF0) then
            // divided by 8, yielding values up to 240; (blockLight<<4) truncated to a byte wiped block
            // light and made merged LOD cells far too dark vs chunk meshes.
            int sumBlock = 0;
            int sumSky = 0;
            for (long state : states) {
                int lm = Mapper.getLightId(state);
                sumBlock += (lm >>> 4) & 0xF;
                sumSky += lm & 0xF;
            }
            int avgBlock = Math.min(15, (sumBlock + 4) / 8);
            int avgSky = Math.min(15, (sumSky + 7) / 8); // ceil(sumSky/8), same bias as before

            return withLight(I111, (avgBlock << 4) | avgSky);
        }
    }
}
