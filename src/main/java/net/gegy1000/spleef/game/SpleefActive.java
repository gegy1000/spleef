package net.gegy1000.spleef.game;

import net.gegy1000.plasmid.game.Game;
import net.gegy1000.plasmid.game.JoinResult;
import net.gegy1000.plasmid.game.event.GameOpenListener;
import net.gegy1000.plasmid.game.event.GameTickListener;
import net.gegy1000.plasmid.game.event.OfferPlayerListener;
import net.gegy1000.plasmid.game.event.PlayerAddListener;
import net.gegy1000.plasmid.game.event.PlayerDamageListener;
import net.gegy1000.plasmid.game.event.PlayerDeathListener;
import net.gegy1000.plasmid.game.event.PlayerRejoinListener;
import net.gegy1000.plasmid.game.map.GameMap;
import net.gegy1000.plasmid.game.rule.GameRule;
import net.gegy1000.plasmid.game.rule.RuleResult;
import net.gegy1000.plasmid.util.ItemStackBuilder;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class SpleefActive {
    private final GameMap map;
    private final SpleefConfig config;

    private final Set<UUID> participants;

    private final SpleefSpawnLogic spawnLogic;

    private final SpleefLevels levels;
    private long nextLevelDropTime = -1;

    private final boolean ignoreWinState;
    private long closeTime = -1;

    private SpleefActive(GameMap map, SpleefConfig config, Set<UUID> participants) {
        this.map = map;
        this.config = config;
        this.participants = new HashSet<>(participants);

        this.ignoreWinState = this.participants.size() <= 1;

        this.spawnLogic = new SpleefSpawnLogic(map);

        this.levels = SpleefLevels.create(map);
    }

    public static Game open(GameMap map, SpleefConfig config, Set<UUID> participants) {
        SpleefActive active = new SpleefActive(map, config, participants);

        Game.Builder builder = Game.builder();
        builder.setMap(map);

        builder.setRule(GameRule.ALLOW_CRAFTING, RuleResult.DENY);
        builder.setRule(GameRule.ALLOW_PORTALS, RuleResult.DENY);
        builder.setRule(GameRule.ALLOW_PVP, RuleResult.DENY);
        builder.setRule(GameRule.BLOCK_DROPS, RuleResult.DENY);
        builder.setRule(GameRule.FALL_DAMAGE, RuleResult.DENY);
        builder.setRule(GameRule.ENABLE_HUNGER, RuleResult.DENY);

        builder.on(GameOpenListener.EVENT, active::open);

        builder.on(OfferPlayerListener.EVENT, (game, player) -> JoinResult.ok());
        builder.on(PlayerAddListener.EVENT, active::addPlayer);

        builder.on(GameTickListener.EVENT, active::tick);

        builder.on(PlayerDamageListener.EVENT, active::onPlayerDamage);
        builder.on(PlayerDeathListener.EVENT, active::onPlayerDeath);
        builder.on(PlayerRejoinListener.EVENT, active::rejoinPlayer);

        return builder.build();
    }

    private void open(Game game) {
        ServerWorld world = game.getWorld();
        for (UUID playerId : this.participants) {
            ServerPlayerEntity player = (ServerPlayerEntity) world.getPlayerByUuid(playerId);
            if (player != null) {
                this.spawnParticipant(player);
            }
        }
    }

    private void addPlayer(Game game, ServerPlayerEntity player) {
        if (!this.participants.contains(player.getUuid())) {
            this.spawnSpectator(player);
        }
    }

    private void rejoinPlayer(Game game, ServerPlayerEntity player) {
        this.spawnSpectator(player);
    }

    private void tick(Game game) {
        ServerWorld world = game.getWorld();
        long time = world.getTime();

        if (this.closeTime > 0) {
            this.tickClosing(game, time);
            return;
        }

        if (time > this.nextLevelDropTime) {
            if (this.nextLevelDropTime != -1) {
                this.levels.tryDropLevel(this.config);
            }

            this.nextLevelDropTime = time + this.config.getLevelBreakInterval();
        } else {
            long ticksToDrop = this.nextLevelDropTime - time;
            if (ticksToDrop % 20 == 0) {
                long secondsToDrop = ticksToDrop / 20;
                if (secondsToDrop == 10 || secondsToDrop < 3) {
                    Text message = new LiteralText("Level dropping in " + secondsToDrop + "...")
                            .formatted(Formatting.DARK_PURPLE, Formatting.BOLD);
                    this.broadcastActionBar(game, message);
                }
            }
        }

        WinResult result = this.checkWinResult(game);
        if (result.isWin()) {
            this.broadcastWin(game, result);
            this.closeTime = time + 20 * 5;
        }
    }

    private void tickClosing(Game game, long time) {
        if (time >= this.closeTime) {
            game.close();
        }
    }

    private void broadcastWin(Game game, WinResult result) {
        ServerPlayerEntity winningPlayer = result.getWinningPlayer();

        Text message;
        if (winningPlayer != null) {
            message = winningPlayer.getDisplayName().shallowCopy().append(" has won the game!").formatted(Formatting.GOLD);
        } else {
            message = new LiteralText("The game ended, but nobody won!").formatted(Formatting.GOLD);
        }

        this.broadcastMessage(game, message);
        this.broadcastSound(game, SoundEvents.ENTITY_VILLAGER_YES);
    }

    private boolean onPlayerDamage(Game game, ServerPlayerEntity player, DamageSource source, float amount) {
        if (source == DamageSource.LAVA) {
            this.eliminatePlayer(game, player);
        }
        return true;
    }

    private boolean onPlayerDeath(Game game, ServerPlayerEntity player, DamageSource source) {
        this.eliminatePlayer(game, player);
        return true;
    }

    private void spawnParticipant(ServerPlayerEntity player) {
        this.spawnLogic.resetPlayer(player, GameMode.ADVENTURE);
        this.spawnLogic.spawnPlayer(player);

        ItemStack shovel = ItemStackBuilder.of(this.config.getTool())
                .setUnbreakable()
                .addEnchantment(Enchantments.EFFICIENCY, 2)
                .addCanDestroy(this.config.getFloor().getBlock())
                .build();

        player.inventory.insertStack(shovel);
    }

    private void eliminatePlayer(Game game, ServerPlayerEntity player) {
        Text message = player.getDisplayName().shallowCopy().append(" has been eliminated!")
                .formatted(Formatting.RED);

        this.broadcastMessage(game, message);
        this.broadcastSound(game, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP);

        this.spawnSpectator(player);

        this.participants.remove(player.getUuid());
    }

    private void spawnSpectator(ServerPlayerEntity player) {
        this.spawnLogic.resetPlayer(player, GameMode.SPECTATOR);
        this.spawnLogic.spawnPlayer(player);
    }

    // TODO: extract common broadcast utils into plasmid
    private void broadcastMessage(Game game, Text message) {
        game.onlinePlayers().forEach(player -> {
            player.sendMessage(message, false);
        });
    }

    private void broadcastActionBar(Game game, Text message) {
        game.onlinePlayers().forEach(player -> {
            player.sendMessage(message, true);
        });
    }

    private void broadcastSound(Game game, SoundEvent sound) {
        game.onlinePlayers().forEach(player -> {
            player.playSound(sound, SoundCategory.PLAYERS, 1.0F, 1.0F);
        });
    }

    private WinResult checkWinResult(Game game) {
        // for testing purposes: don't end the game if we only ever had one participant
        if (this.ignoreWinState) {
            return WinResult.no();
        }

        ServerWorld world = game.getWorld();

        ServerPlayerEntity winningPlayer = null;

        for (UUID playerId : this.participants) {
            ServerPlayerEntity player = (ServerPlayerEntity) world.getPlayerByUuid(playerId);
            if (player != null) {
                // we still have more than one player remaining
                if (winningPlayer != null) {
                    return WinResult.no();
                }

                winningPlayer = player;
            }
        }

        return WinResult.win(winningPlayer);
    }

    static class WinResult {
        final ServerPlayerEntity winningPlayer;
        final boolean win;

        private WinResult(ServerPlayerEntity winningPlayer, boolean win) {
            this.winningPlayer = winningPlayer;
            this.win = win;
        }

        static WinResult no() {
            return new WinResult(null, false);
        }

        static WinResult win(ServerPlayerEntity player) {
            return new WinResult(player, true);
        }

        public boolean isWin() {
            return this.win;
        }

        @Nullable
        public ServerPlayerEntity getWinningPlayer() {
            return this.winningPlayer;
        }
    }
}