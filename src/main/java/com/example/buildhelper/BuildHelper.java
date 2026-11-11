package com.example.buildhelper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Build Helper: paredes, pilares, pisos e telhados com um graveto. Sem comandos.
 *
 * Segure um bloco na MAO ESQUERDA (material) e um GRAVETO na mao direita:
 *  - botao direito no AR:            troca o modo (parede, pilar, piso, telhado)
 *  - SHIFT + botao direito no AR:    troca a altura (1 a 16)
 *  - SHIFT + botao direito num BLOCO: marca os pontos e constroi
 *       pilar: 1 clique | parede, piso, telhado: 2 cliques (inicio e fim / cantos opostos)
 *  - Em sobrevivencia os blocos saem do inventario. Se faltar, avisa e nao constroi nada.
 */
public class BuildHelper implements ModInitializer {
    public static final String MOD_ID = "buildhelper";

    /** Limite de blocos por construcao, pra nao travar o servidor. */
    private static final int MAX_BLOCKS = 2048;

    enum Mode {
        WALL("parede"), PILLAR("pilar"), FLOOR("piso"), ROOF("telhado");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    static class State {
        Mode mode = Mode.WALL;
        int height = 4;
        BlockPos first = null;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    /** Tick do ultimo clique em bloco, pra nao trocar o modo quando o clique foi num bloco. */
    private static final Map<UUID, Long> LAST_BLOCK_USE = new HashMap<>();

    private static State stateOf(Player player) {
        return STATES.computeIfAbsent(player.getUUID(), id -> new State());
    }

    @Override
    public void onInitialize() {
        // Botao direito num bloco com o graveto.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!player.getMainHandItem().is(Items.STICK)) return InteractionResult.PASS;

            if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer sp) {
                LAST_BLOCK_USE.put(sp.getUUID(), serverLevel.getGameTime());
                if (player.isShiftKeyDown()) {
                    handleClick(serverLevel, sp, hit.getBlockPos().relative(hit.getDirection()));
                    return InteractionResult.SUCCESS;
                }
            }
            // Sem shift: deixa o jogo agir normal (abrir bau, etc.).
            return player.isShiftKeyDown() ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });

        // Botao direito no ar com o graveto: troca modo / altura.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!player.getMainHandItem().is(Items.STICK)) return InteractionResult.PASS;

            if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer sp) {
                // Se acabou de clicar num bloco (o jogo chama este evento em seguida), ignora.
                Long last = LAST_BLOCK_USE.get(sp.getUUID());
                if (last != null && serverLevel.getGameTime() - last <= 2) {
                    return InteractionResult.PASS;
                }

                State s = stateOf(sp);
                if (sp.isShiftKeyDown()) {
                    s.height = s.height >= 16 ? 1 : s.height + 1;
                } else {
                    Mode[] modes = Mode.values();
                    s.mode = modes[(s.mode.ordinal() + 1) % modes.length];
                }
                s.first = null;
                msg(sp, "Modo: " + s.mode.label + " | altura: " + s.height);
            }
            return InteractionResult.SUCCESS;
        });
    }

    private static void handleClick(ServerLevel level, ServerPlayer player, BlockPos clicked) {
        // Material = bloco na mao esquerda.
        ItemStack off = player.getOffhandItem();
        if (!(off.getItem() instanceof BlockItem blockItem)) {
            msg(player, "Segure um bloco na mao esquerda (esse e o material).");
            return;
        }

        State s = stateOf(player);

        if (s.mode == Mode.PILLAR) {
            Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
            BlockState state = blockItem.getBlock().defaultBlockState();
            for (int h = 0; h < s.height; h++) {
                plan.put(clicked.above(h), state);
            }
            build(level, player, blockItem, plan);
            return;
        }

        if (s.first == null) {
            s.first = clicked;
            msg(player, "Ponto 1 marcado. Clique no ponto 2.");
            return;
        }

        BlockPos a = s.first;
        s.first = null;

        Map<BlockPos, BlockState> plan = switch (s.mode) {
            case WALL -> planWall(blockItem.getBlock().defaultBlockState(), a, clicked, s.height);
            case FLOOR -> planFloor(blockItem.getBlock().defaultBlockState(), a, clicked);
            case ROOF -> planRoof(blockItem.getBlock().defaultBlockState(), a, clicked);
            default -> new LinkedHashMap<>();
        };

        build(level, player, blockItem, plan);
    }

    // ---------------------------------------------------------------- formas

    /** Parede reta de A ate B (no eixo mais longo), subindo 'height' blocos a partir do y de A. */
    private static Map<BlockPos, BlockState> planWall(BlockState state, BlockPos a, BlockPos b, int height) {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();

        int dx = Math.abs(b.getX() - a.getX());
        int dz = Math.abs(b.getZ() - a.getZ());
        int endX = a.getX();
        int endZ = a.getZ();
        if (dx >= dz) endX = b.getX(); else endZ = b.getZ();

        for (int x = Math.min(a.getX(), endX); x <= Math.max(a.getX(), endX); x++) {
            for (int z = Math.min(a.getZ(), endZ); z <= Math.max(a.getZ(), endZ); z++) {
                for (int h = 0; h < height; h++) {
                    plan.put(new BlockPos(x, a.getY() + h, z), state);
                }
            }
        }
        return plan;
    }

    /** Piso retangular entre dois cantos opostos, na altura de A. */
    private static Map<BlockPos, BlockState> planFloor(BlockState state, BlockPos a, BlockPos b) {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        for (int x = Math.min(a.getX(), b.getX()); x <= Math.max(a.getX(), b.getX()); x++) {
            for (int z = Math.min(a.getZ(), b.getZ()); z <= Math.max(a.getZ(), b.getZ()); z++) {
                plan.put(new BlockPos(x, a.getY(), z), state);
            }
        }
        return plan;
    }

    /**
     * Telhado de duas aguas sobre a area entre A e B. A cumeeira corre no lado mais longo.
     * Se o bloco tiver direcao (degrau), cada fileira e virada pra cumeeira.
     */
    private static Map<BlockPos, BlockState> planRoof(BlockState state, BlockPos a, BlockPos b) {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();

        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxZ = Math.max(a.getZ(), b.getZ());
        int baseY = a.getY();

        boolean ridgeAlongX = (maxX - minX) >= (maxZ - minZ);

        // Eixo curto: e por ele que o telhado sobe.
        int width = ridgeAlongX ? (maxZ - minZ + 1) : (maxX - minX + 1);
        Direction towardHigh = ridgeAlongX ? Direction.SOUTH : Direction.EAST;
        Direction towardLow = towardHigh.getOpposite();

        for (int k = 0; k < width; k++) {
            int h = Math.min(k, width - 1 - k);
            Direction facing = (k <= width - 1 - k) ? towardHigh : towardLow;

            BlockState rowState = state;
            if (rowState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                rowState = rowState.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            }

            if (ridgeAlongX) {
                for (int x = minX; x <= maxX; x++) {
                    plan.put(new BlockPos(x, baseY + h, minZ + k), rowState);
                }
            } else {
                for (int z = minZ; z <= maxZ; z++) {
                    plan.put(new BlockPos(minX + k, baseY + h, z), rowState);
                }
            }
        }
        return plan;
    }

    // ------------------------------------------------------------ construcao

    private static void build(ServerLevel level, ServerPlayer player, BlockItem blockItem, Map<BlockPos, BlockState> plan) {
        // So conta/coloca onde da pra substituir (ar, grama alta, etc.).
        Map<BlockPos, BlockState> toPlace = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : plan.entrySet()) {
            BlockState current = level.getBlockState(entry.getKey());
            if (current.isAir() || current.canBeReplaced()) {
                toPlace.put(entry.getKey(), entry.getValue());
            }
        }

        int needed = toPlace.size();
        if (needed == 0) {
            msg(player, "Nada pra construir ai (o espaco ja esta ocupado).");
            return;
        }
        if (needed > MAX_BLOCKS) {
            msg(player, "Grande demais: " + needed + " blocos (maximo " + MAX_BLOCKS + ").");
            return;
        }

        boolean creative = player.isCreative();
        Item item = blockItem;

        if (!creative) {
            int have = countItems(player.getInventory(), item);
            if (have < needed) {
                msg(player, "Faltam " + (needed - have) + " blocos (precisa de " + needed + ", tem " + have + ").");
                return;
            }
        }

        for (Map.Entry<BlockPos, BlockState> entry : toPlace.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
        }

        if (!creative) {
            consumeItems(player.getInventory(), item, needed);
        }

        msg(player, "Pronto: " + needed + " blocos.");
    }

    private static int countItems(Inventory inv, Item item) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /** Tira do inventario; a mao esquerda e a ultima a ser usada. */
    private static void consumeItems(Inventory inv, Item item, int amount) {
        int left = amount;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) {
                int take = Math.min(left, stack.getCount());
                stack.shrink(take);
                left -= take;
            }
        }
    }

    private static void msg(Player player, String text) {
        player.sendSystemMessage(Component.literal(text));
    }
}
