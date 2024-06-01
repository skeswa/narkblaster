/*
 * Copyright (c) 2016-2017, Abel Briggs
 * Copyright (c) 2017, Kronos <https://github.com/KronosDesign>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.narkblaster;

import com.google.inject.Provides;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import net.runelite.http.api.RuneLiteAPI;
import net.runelite.client.util.ImageCapture;

import javax.swing.JOptionPane;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import javax.inject.Provider;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static net.runelite.api.AnimationID.*;

@PluginDescriptor(
        name = "Nark Blaster",
        description = "Blasts the narks to smithereens",
        tags = {"nark", "citizen", "blaster"},
        enabledByDefault = true
)
public class NarkBlasterPlugin extends Plugin {
    private static final int IDLE_LOGOUT_WARNING_BUFFER = 20_000 / Constants.CLIENT_TICK_LENGTH;
    private static final int COMBAT_WARNING_MILLIS = 19 * 60 * 1000; // 19 minutes
    private static final int COMBAT_WARNING_CLIENT_TICKS = COMBAT_WARNING_MILLIS / Constants.CLIENT_TICK_LENGTH;

    private static final int HIGHEST_MONSTER_ATTACK_SPEED = 8; // Except Scarab Mage, but they are with other monsters
    private static final Duration SIX_HOUR_LOGOUT_WARNING_AFTER_DURATION = Duration.ofMinutes(340);

    private static final String FISHING_SPOT = "Fishing spot";
    public static final String REPORT_CRIME = "Report Crime";

    private String crimesString;
    private String lastTarget;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private Notifier notifier;

    @Inject
    private Client client;

    @Inject
    private NarkBlasterConfig config;

    @Inject
    private Provider<MenuManager> menuManager;

    private Instant lastAnimating;
    private int lastAnimation = IDLE;
    private Instant lastInteracting;
    private Actor lastInteract;
    private Instant lastMoving;
    private WorldPoint lastPosition;
    private boolean notifyPosition = false;
    private boolean notifyHitpoints = true;
    private boolean notifyPrayer = true;
    private boolean shouldNotifyLowEnergy = false;
    private boolean shouldNotifyHighEnergy = false;
    private boolean notifyOxygen = true;
    private boolean notifyIdleLogout = true;
    private boolean notify6HourLogout = true;
    private int lastSpecEnergy = 1000;
    private int lastCombatCountdown = 0;
    private Instant sixHourWarningTime;
    private boolean ready;
    private boolean lastInteractWasCombat;
    private static final int BUFF_BAR_NOT_DISPLAYED = -1;

    @Provides
    NarkBlasterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(NarkBlasterConfig.class);
    }

    @Override
    protected void startUp() {
        // can't tell when 6hr will be if enabled while already logged in
        sixHourWarningTime = null;

        menuManager.get().addPlayerMenuItem(REPORT_CRIME);
        System.out.println("got here 5");

        // Create a ScheduledExecutorService with one thread
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

        // Schedule a task to run every 2 seconds
        executorService.scheduleAtFixedRate(() -> {
            try {
                System.out.println("Polling...");

                var playerLocation = client.getLocalPlayer().getWorldLocation();

                // Build the HttpUrl
                HttpUrl url = new HttpUrl.Builder()
                        .scheme("http")
                        .host("sagnew.ngrok.io")
                        .addPathSegment("getcrimes")
                        .addQueryParameter("x", String.valueOf(playerLocation.getX()))
                        .addQueryParameter("y", String.valueOf(playerLocation.getY()))
                        .build();

                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();

                okHttpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        System.out.println("onFailure polling");
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        System.out.println("onResponse polling");

                        try {
                            // Get the response body
                            ResponseBody responseBody = response.body();

                            if (responseBody != null) {
                                // Convert the response body to a string
                                String responseString = responseBody.string();

                                if (responseString.equals(crimesString)) {
                                    System.out.println("No new crimes");
                                    return;
                                }

                                crimesString = responseString;

                                // Split the response string by newlines
                                String[] lines = responseString.split("\n");

                                if (lines.length > 0) {
                                    var x = lines[lines.length - 1];
                                    if (x != null) {
                                        if (lastTarget != null && x.indexOf(lastTarget) > 0) {
                                            System.out.println("Skipping our report");
                                            return;
                                        }

                                        JOptionPane.showMessageDialog(null, x, "NEW CRIME REPORTED", JOptionPane.INFORMATION_MESSAGE);
                                    }


                                }
                            }
                        } catch (Throwable t) {
                            System.out.println("onFailure polling while parsing text");
                            t.printStackTrace();
                        }
                        response.close();
                    }
                });
            } catch (Throwable t) {
                System.out.println("Throw while polling");
            }
        }, 5, 5, TimeUnit.SECONDS);

        // Optionally, add a shutdown hook to gracefully shutdown the executor service
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.out.println("Executor did not terminate in the specified time.");
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
            System.out.println("Shutdown complete.");
        }));
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        if (event.getTarget().contains("Player")) {
            client.createMenuEntry(-1)
                    .setOption(REPORT_CRIME)
                    .setTarget(event.getTarget())
                    .setType(MenuAction.RUNELITE)
                    .setIdentifier(event.getIdentifier())
//					.setOpcode(event.getOpcode())
//					.setParam0(event.getActionParam0())
//					.setParam1(event.getActionParam1());
                    .onClick(e ->
                    {
//						// Determine proper endpoint from player name.
//						// TODO: look at target's world and determine if tournament/dmm endpoint should be used instead.
//						HiscoreEndpoint endpoint = findHiscoreEndpointFromPlayerName(e.getTarget());
//						String target = Text.removeTags(e.getTarget());
//						lookupPlayer(target, endpoint);
                        System.out.println("got here");
                    });
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
//		if (Text.removeTags(event.getOption()).equals("Report Crime")) {
//			// Handle the action for reporting a criminal
//			Player target = client.getCachedPlayers()[event.getIdentifier()];
//			if (target != null) {
//				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "You have reported " + target.getName() + " as a criminal.", null);
//				// Additional reporting logic here
//			}
//		}
        if (event.getMenuAction() == MenuAction.RUNELITE_PLAYER && event.getMenuOption().equals(REPORT_CRIME)) {

            Player player = event.getMenuEntry().getPlayer();
            if (player == null) {
                System.out.println("got here 2");
                return;
            }

            String target = player.getName();
            if (target == null) {
                target = "shrug";
            }
            this.lastTarget = target;

            String userInput = JOptionPane.showInputDialog(null,
                    "DESCRIBE THE DEED:",
                    "Crime Report",
                    JOptionPane.QUESTION_MESSAGE);

            var playerLocation = client.getLocalPlayer().getWorldLocation();

            // Build the HttpUrl
            HttpUrl url = new HttpUrl.Builder()
                    .scheme("http")
                    .host("sagnew.ngrok.io")
                    .addPathSegment("report")
                    .addQueryParameter("x", String.valueOf(playerLocation.getX()))
                    .addQueryParameter("y", String.valueOf(playerLocation.getY()))
                    .build();

            // Get the current date and time
            LocalDateTime currentDateTime = LocalDateTime.now();

            // Define a human-friendly format
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy HH:mm:ss a");

            // Format the date and time
            String formattedDateTime = currentDateTime.format(formatter);

            RequestBody formBody = new FormBody.Builder()
                    .add("crime", userInput)
                    .add("username", target)
                    .add("time", formattedDateTime)
                    .build();

            Request request = new Request.Builder()
                    .url(url)

                    .post(formBody)
                    .build();

            okHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    System.out.println("onFailure");
                }

                @Override
                public void onResponse(Call call, Response response) {
                    System.out.println("onResponse");
                    response.close();
                }
            });
//			HiscoreEndpoint endpoint = getWorldEndpoint();
//
//			lookupPlayer(target, endpoint);
            System.out.println("got here 3 " + target);
        }
    }

    private @NotNull String getPlayerLocationJson() {
        WorldPoint playerLocation = client.getLocalPlayer().getWorldLocation();
        return "{\"x\":" + String.valueOf(playerLocation.getX()) + ",\"y\":" + String.valueOf(playerLocation.getY()) + "}";
//        return playerLocation.toString().replace("WorldPoint", "").replace('(', '{').replace(')', '}').replace('=', ':');
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (client.getGameState() != GameState.LOGGED_IN) {
            return;
        }

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer != event.getActor()) {
            return;
        }

        int graphic = localPlayer.getGraphic();
        int animation = localPlayer.getAnimation();
        switch (animation) {
            /* Woodcutting */
            case WOODCUTTING_BRONZE:
            case WOODCUTTING_IRON:
            case WOODCUTTING_STEEL:
            case WOODCUTTING_BLACK:
            case WOODCUTTING_MITHRIL:
            case WOODCUTTING_ADAMANT:
            case WOODCUTTING_RUNE:
            case WOODCUTTING_GILDED:
            case WOODCUTTING_DRAGON:
            case WOODCUTTING_DRAGON_OR:
            case WOODCUTTING_INFERNAL:
            case WOODCUTTING_3A_AXE:
            case WOODCUTTING_CRYSTAL:
            case WOODCUTTING_TRAILBLAZER:
            case WOODCUTTING_2H_BRONZE:
            case WOODCUTTING_2H_IRON:
            case WOODCUTTING_2H_STEEL:
            case WOODCUTTING_2H_BLACK:
            case WOODCUTTING_2H_MITHRIL:
            case WOODCUTTING_2H_ADAMANT:
            case WOODCUTTING_2H_RUNE:
            case WOODCUTTING_2H_DRAGON:
            case WOODCUTTING_2H_CRYSTAL:
            case WOODCUTTING_2H_CRYSTAL_INACTIVE:
            case WOODCUTTING_2H_3A:
                /* Woodcutting: Ents & Canoes */
            case WOODCUTTING_ENT_BRONZE:
            case WOODCUTTING_ENT_IRON:
            case WOODCUTTING_ENT_STEEL:
            case WOODCUTTING_ENT_BLACK:
            case WOODCUTTING_ENT_MITHRIL:
            case WOODCUTTING_ENT_ADAMANT:
            case WOODCUTTING_ENT_RUNE:
            case WOODCUTTING_ENT_GILDED:
            case WOODCUTTING_ENT_DRAGON:
            case WOODCUTTING_ENT_DRAGON_OR:
            case WOODCUTTING_ENT_INFERNAL:
            case WOODCUTTING_ENT_INFERNAL_OR:
            case WOODCUTTING_ENT_3A:
            case WOODCUTTING_ENT_CRYSTAL:
            case WOODCUTTING_ENT_CRYSTAL_INACTIVE:
            case WOODCUTTING_ENT_TRAILBLAZER:
            case WOODCUTTING_ENT_2H_BRONZE:
            case WOODCUTTING_ENT_2H_IRON:
            case WOODCUTTING_ENT_2H_STEEL:
            case WOODCUTTING_ENT_2H_BLACK:
            case WOODCUTTING_ENT_2H_MITHRIL:
            case WOODCUTTING_ENT_2H_ADAMANT:
            case WOODCUTTING_ENT_2H_RUNE:
            case WOODCUTTING_ENT_2H_DRAGON:
            case WOODCUTTING_ENT_2H_CRYSTAL:
            case WOODCUTTING_ENT_2H_CRYSTAL_INACTIVE:
            case WOODCUTTING_ENT_2H_3A:
            case BLISTERWOOD_JUMP_SCARE:
                /* Firemaking */
            case FIREMAKING_FORESTERS_CAMPFIRE_ARCTIC_PINE:
            case FIREMAKING_FORESTERS_CAMPFIRE_BLISTERWOOD:
            case FIREMAKING_FORESTERS_CAMPFIRE_LOGS:
            case FIREMAKING_FORESTERS_CAMPFIRE_MAGIC:
            case FIREMAKING_FORESTERS_CAMPFIRE_MAHOGANY:
            case FIREMAKING_FORESTERS_CAMPFIRE_MAPLE:
            case FIREMAKING_FORESTERS_CAMPFIRE_OAK:
            case FIREMAKING_FORESTERS_CAMPFIRE_REDWOOD:
            case FIREMAKING_FORESTERS_CAMPFIRE_TEAK:
            case FIREMAKING_FORESTERS_CAMPFIRE_WILLOW:
            case FIREMAKING_FORESTERS_CAMPFIRE_YEW:
                /* Cooking(Fire, Range) */
            case COOKING_FIRE:
            case COOKING_RANGE:
            case COOKING_WINE:
                /* Crafting(Gem Cutting, Glassblowing, Spinning, Weaving, Battlestaves, Pottery) */
            case GEM_CUTTING_OPAL:
            case GEM_CUTTING_JADE:
            case GEM_CUTTING_REDTOPAZ:
            case GEM_CUTTING_SAPPHIRE:
            case GEM_CUTTING_EMERALD:
            case GEM_CUTTING_RUBY:
            case GEM_CUTTING_DIAMOND:
            case GEM_CUTTING_AMETHYST:
            case CRAFTING_GLASSBLOWING:
            case CRAFTING_SPINNING:
            case CRAFTING_LOOM:
            case CRAFTING_BATTLESTAVES:
            case CRAFTING_LEATHER:
            case CRAFTING_POTTERS_WHEEL:
            case CRAFTING_POTTERY_OVEN:
                /* Fletching(Cutting, Stringing, Adding feathers and heads) */
            case FLETCHING_BOW_CUTTING:
            case FLETCHING_STRING_NORMAL_SHORTBOW:
            case FLETCHING_STRING_OAK_SHORTBOW:
            case FLETCHING_STRING_WILLOW_SHORTBOW:
            case FLETCHING_STRING_MAPLE_SHORTBOW:
            case FLETCHING_STRING_YEW_SHORTBOW:
            case FLETCHING_STRING_MAGIC_SHORTBOW:
            case FLETCHING_STRING_NORMAL_LONGBOW:
            case FLETCHING_STRING_OAK_LONGBOW:
            case FLETCHING_STRING_WILLOW_LONGBOW:
            case FLETCHING_STRING_MAPLE_LONGBOW:
            case FLETCHING_STRING_YEW_LONGBOW:
            case FLETCHING_STRING_MAGIC_LONGBOW:
            case FLETCHING_ATTACH_FEATHERS_TO_ARROWSHAFT:
            case FLETCHING_ATTACH_HEADS:
            case FLETCHING_ATTACH_BOLT_TIPS_TO_BRONZE_BOLT:
            case FLETCHING_ATTACH_BOLT_TIPS_TO_IRON_BROAD_BOLT:
            case FLETCHING_ATTACH_BOLT_TIPS_TO_BLURITE_BOLT:
            case FLETCHING_ATTACH_BOLT_TIPS_TO_STEEL_BOLT:
            case FLETCHING_ATTACH_BOLT_TIPS_TO_MITHRIL_BOLT:
            case FLETCHING_ATTACH_BOLT_TIPS_TO_ADAMANT_BOLT:
            case FLETCHING_ATTACH_BOLT_TIPS_TO_RUNE_BOLT:
            case FLETCHING_ATTACH_BOLT_TIPS_TO_DRAGON_BOLT:
                /* Smithing(Anvil, Furnace, Cannonballs */
            case SMITHING_ANVIL:
            case SMITHING_IMCANDO_HAMMER:
            case SMITHING_SMELTING:
            case SMITHING_CANNONBALL:
                /* Fishing */
            case FISHING_CRUSHING_INFERNAL_EELS:
            case FISHING_CRUSHING_INFERNAL_EELS_IMCANDO_HAMMER:
            case FISHING_CUTTING_SACRED_EELS:
            case FISHING_BIG_NET:
            case FISHING_NET:
            case FISHING_POLE_CAST:
            case FISHING_CAGE:
            case FISHING_HARPOON:
            case FISHING_BARBTAIL_HARPOON:
            case FISHING_DRAGON_HARPOON:
            case FISHING_DRAGON_HARPOON_OR:
            case FISHING_INFERNAL_HARPOON:
            case FISHING_CRYSTAL_HARPOON:
            case FISHING_TRAILBLAZER_HARPOON:
            case FISHING_OILY_ROD:
            case FISHING_KARAMBWAN:
            case FISHING_BAREHAND:
            case FISHING_PEARL_ROD:
            case FISHING_PEARL_FLY_ROD:
            case FISHING_PEARL_BARBARIAN_ROD:
            case FISHING_PEARL_ROD_2:
            case FISHING_PEARL_FLY_ROD_2:
            case FISHING_PEARL_BARBARIAN_ROD_2:
            case FISHING_PEARL_OILY_ROD:
            case FISHING_BARBARIAN_ROD:
                /* Mining(Normal) */
            case MINING_BRONZE_PICKAXE:
            case MINING_IRON_PICKAXE:
            case MINING_STEEL_PICKAXE:
            case MINING_BLACK_PICKAXE:
            case MINING_MITHRIL_PICKAXE:
            case MINING_ADAMANT_PICKAXE:
            case MINING_RUNE_PICKAXE:
            case MINING_GILDED_PICKAXE:
            case MINING_DRAGON_PICKAXE:
            case MINING_DRAGON_PICKAXE_UPGRADED:
            case MINING_DRAGON_PICKAXE_OR:
            case MINING_DRAGON_PICKAXE_OR_TRAILBLAZER:
            case MINING_INFERNAL_PICKAXE:
            case MINING_3A_PICKAXE:
            case MINING_CRYSTAL_PICKAXE:
            case MINING_TRAILBLAZER_PICKAXE:
            case MINING_TRAILBLAZER_PICKAXE_2:
            case MINING_TRAILBLAZER_PICKAXE_3:
            case DENSE_ESSENCE_CHIPPING:
            case DENSE_ESSENCE_CHISELING:
                /* Mining(Motherlode) */
            case MINING_MOTHERLODE_BRONZE:
            case MINING_MOTHERLODE_IRON:
            case MINING_MOTHERLODE_STEEL:
            case MINING_MOTHERLODE_BLACK:
            case MINING_MOTHERLODE_MITHRIL:
            case MINING_MOTHERLODE_ADAMANT:
            case MINING_MOTHERLODE_RUNE:
            case MINING_MOTHERLODE_GILDED:
            case MINING_MOTHERLODE_DRAGON:
            case MINING_MOTHERLODE_DRAGON_UPGRADED:
            case MINING_MOTHERLODE_DRAGON_OR:
            case MINING_MOTHERLODE_DRAGON_OR_TRAILBLAZER:
            case MINING_MOTHERLODE_INFERNAL:
            case MINING_MOTHERLODE_3A:
            case MINING_MOTHERLODE_CRYSTAL:
            case MINING_MOTHERLODE_TRAILBLAZER:
                /* Mining(Crashed Star) */
            case MINING_CRASHEDSTAR_BRONZE:
            case MINING_CRASHEDSTAR_IRON:
            case MINING_CRASHEDSTAR_STEEL:
            case MINING_CRASHEDSTAR_BLACK:
            case MINING_CRASHEDSTAR_MITHRIL:
            case MINING_CRASHEDSTAR_ADAMANT:
            case MINING_CRASHEDSTAR_RUNE:
            case MINING_CRASHEDSTAR_GILDED:
            case MINING_CRASHEDSTAR_DRAGON:
            case MINING_CRASHEDSTAR_DRAGON_UPGRADED:
            case MINING_CRASHEDSTAR_DRAGON_OR:
            case MINING_CRASHEDSTAR_DRAGON_OR_TRAILBLAZER:
            case MINING_CRASHEDSTAR_INFERNAL:
            case MINING_CRASHEDSTAR_3A:
            case MINING_CRASHEDSTAR_CRYSTAL:
                /* Herblore */
            case HERBLORE_PESTLE_AND_MORTAR:
            case HERBLORE_POTIONMAKING:
            case HERBLORE_MAKE_TAR:
                /* Magic */
            case MAGIC_CHARGING_ORBS:
            case MAGIC_LUNAR_PLANK_MAKE:
            case MAGIC_LUNAR_STRING_JEWELRY:
            case MAGIC_MAKE_TABLET:
            case MAGIC_ENCHANTING_JEWELRY:
            case MAGIC_ENCHANTING_AMULET_1:
            case MAGIC_ENCHANTING_AMULET_2:
            case MAGIC_ENCHANTING_AMULET_3:
            case MAGIC_ENCHANTING_BOLTS:
                /* Prayer */
            case USING_GILDED_ALTAR:
            case ECTOFUNTUS_FILL_SLIME_BUCKET:
            case ECTOFUNTUS_INSERT_BONES:
            case ECTOFUNTUS_GRIND_BONES:
            case ECTOFUNTUS_EMPTY_BIN:
                /* Farming */
            case FARMING_MIX_ULTRACOMPOST:
            case FARMING_HARVEST_BUSH:
            case FARMING_HARVEST_HERB:
            case FARMING_HARVEST_FRUIT_TREE:
            case FARMING_HARVEST_FLOWER:
            case FARMING_HARVEST_ALLOTMENT:
                /* Misc */
            case PISCARILIUS_CRANE_REPAIR:
            case HOME_MAKE_TABLET:
            case SAND_COLLECTION:
            case MILKING_COW:
            case CHURN_MILK_SHORT:
            case CHURN_MILK_MEDIUM:
            case CHURN_MILK_LONG:
            case CLEANING_SPECIMENS_1:
            case CLEANING_SPECIMENS_2:
            case LOOKING_INTO:
                resetTimers();
                lastAnimation = animation;
                lastAnimating = Instant.now();
                break;
            case MAGIC_LUNAR_SHARED:
                if (graphic == GraphicID.BAKE_PIE) {
                    resetTimers();
                    lastAnimation = animation;
                    lastAnimating = Instant.now();
                    break;
                }
            case IDLE:
                lastAnimating = Instant.now();
                break;
            default:
                // On unknown animation simply assume the animation is invalid and dont throw notification
                lastAnimation = IDLE;
                lastAnimating = null;
        }
    }

    @Subscribe
    public void onInteractingChanged(InteractingChanged event) {
        final Actor source = event.getSource();
        if (source != client.getLocalPlayer()) {
            return;
        }

        final Actor target = event.getTarget();

        // Reset last interact
        if (target != null) {
            lastInteract = null;
        } else {
            lastInteracting = Instant.now();
        }

        // If this is not NPC, do not process as we are not interested in other entities
        if (!(target instanceof NPC)) {
            return;
        }

        checkNpcInteraction((NPC) target);
    }

    // this event is needed to handle some rare npcs where "Attack" is not used to initiate combat
    // for example, kraken starts the fight with "Disturb" then changes into another form with "Attack"
    @Subscribe
    public void onNpcChanged(NpcChanged event) {
        NPC npc = event.getNpc();
        if (client.getLocalPlayer().getInteracting() != npc) {
            return;
        }

        checkNpcInteraction(npc);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        lastInteracting = null;

        GameState state = gameStateChanged.getGameState();

        switch (state) {
            case LOGIN_SCREEN:
                resetTimers();
                break;
            case LOGGING_IN:
            case HOPPING:
            case CONNECTION_LOST:
                ready = true;
                break;
            case LOGGED_IN:
                if (ready) {
                    sixHourWarningTime = Instant.now().plus(SIX_HOUR_LOGOUT_WARNING_AFTER_DURATION);
                    ready = false;
                    resetTimers();
                }

                break;
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        if (event.getActor() != client.getLocalPlayer()) {
            return;
        }

        final Hitsplat hitsplat = event.getHitsplat();
        if (hitsplat.isMine()) {
            lastCombatCountdown = HIGHEST_MONSTER_ATTACK_SPEED;
        }
    }

    @Subscribe
    public void onGraphicChanged(GraphicChanged event) {
        Actor actor = event.getActor();

        if (actor != client.getLocalPlayer()) {
            return;
        }

        if (actor.getGraphic() == GraphicID.SPLASH) {
            lastCombatCountdown = HIGHEST_MONSTER_ATTACK_SPEED;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        final Player local = client.getLocalPlayer();
        final Duration waitDuration = Duration.ofMillis(config.getIdleNotificationDelay());
        lastCombatCountdown = Math.max(lastCombatCountdown - 1, 0);

        if (client.getGameState() != GameState.LOGGED_IN
                || local == null
                // If user has clicked in the last second then they're not idle so don't send idle notification
                || System.currentTimeMillis() - client.getMouseLastPressedMillis() < 1000
                || client.getKeyboardIdleTicks() < 10) {
            resetTimers();
            return;
        }

        if (checkIdleLogout()) {
            notifier.notify(config.logoutIdle(), "You are about to log out from idling too long!");
        }

        if (check6hrLogout()) {
            notifier.notify("You are about to log out from being online for 6 hours!");
        }

        if (checkAnimationIdle(waitDuration, local)) {
            notifier.notify(config.animationIdle(), "You are now idle!");
        }

        if (checkMovementIdle(waitDuration, local)) {
            notifier.notify(config.movementIdle(), "You have stopped moving!");
        }

        if (checkInteractionIdle(waitDuration, local)) {
            if (lastInteractWasCombat) {
                notifier.notify(config.interactionIdle(), "You are now out of combat!");
            } else {
                notifier.notify(config.interactionIdle(), "You are now idle!");
            }
        }

        if (checkLowHitpoints()) {
            notifier.notify("You have low hitpoints!");
        }

        if (checkLowPrayer()) {
            notifier.notify("You have low prayer!");
        }

        if (checkLowEnergy()) {
            notifier.notify("You have low run energy!");
        }

        if (checkHighEnergy()) {
            notifier.notify("You have restored run energy!");
        }

        if (checkLowOxygen()) {
            notifier.notify("You have low oxygen!");
        }

        if (checkFullSpecEnergy()) {
            notifier.notify("You have restored spec energy!");
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        if (event.getVarpId() == VarPlayer.BUFF_BAR_WC_GROUP_BONUS && event.getValue() == BUFF_BAR_NOT_DISPLAYED) {
            resetTimers();
            lastAnimation = WOODCUTTING_RUNE;
            lastAnimating = Instant.now();
        }
    }

    private void checkNpcInteraction(final NPC target) {
        final NPCComposition npcComposition = target.getComposition();
        final List<String> npcMenuActions = Arrays.asList(npcComposition.getActions());

        if (npcMenuActions.contains("Attack")) {
            // Player is most likely in combat with attack-able NPC
            resetTimers();
            lastInteract = target;
            lastInteracting = Instant.now();
            lastInteractWasCombat = true;
        } else if (target.getName() != null && target.getName().contains(FISHING_SPOT)) {
            // Player is fishing
            resetTimers();
            lastInteract = target;
            lastInteracting = Instant.now();
            lastInteractWasCombat = false;
        }
    }

    private boolean checkFullSpecEnergy() {
        int currentSpecEnergy = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT);

        int threshold = config.getSpecEnergyThreshold() * 10;
        if (threshold == 0) {
            lastSpecEnergy = currentSpecEnergy;
            return false;
        }

        // Check if we have regenerated over the threshold, and that the
        // regen was small enough.
        boolean notify = lastSpecEnergy < threshold && currentSpecEnergy >= threshold
                && currentSpecEnergy - lastSpecEnergy <= 100;
        lastSpecEnergy = currentSpecEnergy;
        return notify;
    }

    private boolean checkLowOxygen() {
        if (config.getOxygenThreshold() == 0) {
            return false;
        }
        if (config.getOxygenThreshold() >= client.getVarbitValue(Varbits.OXYGEN_LEVEL) * 0.1) {
            if (!notifyOxygen) {
                notifyOxygen = true;
                return true;
            }
        } else {
            notifyOxygen = false;
        }
        return false;
    }

    private boolean checkLowHitpoints() {
        if (config.getHitpointsThreshold() == 0) {
            return false;
        }
        if (client.getRealSkillLevel(Skill.HITPOINTS) > config.getHitpointsThreshold()) {
            if (client.getBoostedSkillLevel(Skill.HITPOINTS) + client.getVarbitValue(Varbits.NMZ_ABSORPTION) <= config.getHitpointsThreshold()) {
                if (!notifyHitpoints) {
                    notifyHitpoints = true;
                    return true;
                }
            } else {
                notifyHitpoints = false;
            }
        }

        return false;
    }

    private boolean checkLowPrayer() {
        if (config.getPrayerThreshold() == 0) {
            return false;
        }
        if (client.getRealSkillLevel(Skill.PRAYER) > config.getPrayerThreshold()) {
            if (client.getBoostedSkillLevel(Skill.PRAYER) <= config.getPrayerThreshold()) {
                if (!notifyPrayer) {
                    notifyPrayer = true;
                    return true;
                }
            } else {
                notifyPrayer = false;
            }
        }

        return false;
    }

    private boolean checkLowEnergy() {
        if (config.getLowEnergyThreshold() >= 100) {
            return false;
        }

        if (client.getEnergy() / 100 <= config.getLowEnergyThreshold()) {
            if (shouldNotifyLowEnergy) {
                shouldNotifyLowEnergy = false;
                return true;
            }
        } else {
            shouldNotifyLowEnergy = true;
        }

        return false;
    }

    private boolean checkHighEnergy() {
        if (config.getHighEnergyThreshold() == 0) {
            return false;
        }

        if (client.getEnergy() / 100 >= config.getHighEnergyThreshold()) {
            if (shouldNotifyHighEnergy) {
                shouldNotifyHighEnergy = false;
                return true;
            }
        } else {
            shouldNotifyHighEnergy = true;
        }

        return false;
    }

    private boolean checkInteractionIdle(Duration waitDuration, Player local) {
        if (lastInteract == null) {
            return false;
        }

        final Actor interact = local.getInteracting();

        if (interact == null) {
            if (lastInteracting != null
                    && Instant.now().compareTo(lastInteracting.plus(waitDuration)) >= 0
                    && lastCombatCountdown == 0) {
                lastInteract = null;
                lastInteracting = null;

                // prevent animation notifications from firing too
                lastAnimation = IDLE;
                lastAnimating = null;

                return true;
            }
        } else {
            lastInteracting = Instant.now();
        }

        return false;
    }

    private boolean checkIdleLogout() {
        // Check clientside AFK first, because this is required for the server to disconnect you for being first
        final int idleClientTicks = Math.min(client.getKeyboardIdleTicks(), client.getMouseIdleTicks());

        if (idleClientTicks < client.getIdleTimeout() - IDLE_LOGOUT_WARNING_BUFFER) {
            notifyIdleLogout = true;
            return false;
        }

        // If we are not receiving hitsplats then we can be afk kicked
        if (lastCombatCountdown <= 0) {
            boolean warn = notifyIdleLogout;
            notifyIdleLogout = false;
            return warn;
        }

        // We are in combat, so now we have to check for the timer that knocks you out of combat
        // I think there are other conditions that I don't know about, because during testing I just didn't
        // get removed from combat sometimes.
        final long lastInteractionAgo = System.currentTimeMillis() - client.getMouseLastPressedMillis();
        if (lastInteractionAgo < COMBAT_WARNING_MILLIS || client.getKeyboardIdleTicks() < COMBAT_WARNING_CLIENT_TICKS) {
            notifyIdleLogout = true;
            return false;
        }

        boolean warn = notifyIdleLogout;
        notifyIdleLogout = false;
        return warn;
    }

    private boolean check6hrLogout() {
        if (sixHourWarningTime == null) {
            return false;
        }

        if (Instant.now().compareTo(sixHourWarningTime) >= 0) {
            if (notify6HourLogout) {
                notify6HourLogout = false;
                return true;
            }
        } else {
            notify6HourLogout = true;
        }

        return false;
    }

    private boolean checkAnimationIdle(Duration waitDuration, Player local) {
        if (lastAnimation == IDLE) {
            return false;
        }

        final int animation = local.getAnimation();

        if (animation == IDLE) {
            if (lastAnimating != null && Instant.now().compareTo(lastAnimating.plus(waitDuration)) >= 0) {
                lastAnimation = IDLE;
                lastAnimating = null;

                // prevent interaction notifications from firing too
                lastInteract = null;
                lastInteracting = null;

                return true;
            }
        } else {
            lastAnimating = Instant.now();
        }

        return false;
    }

    private boolean checkMovementIdle(Duration waitDuration, Player local) {
        if (lastPosition == null) {
            lastPosition = local.getWorldLocation();
            return false;
        }

        WorldPoint position = local.getWorldLocation();

        if (lastPosition.equals(position)) {
            if (notifyPosition
                    && local.getAnimation() == IDLE
                    && Instant.now().compareTo(lastMoving.plus(waitDuration)) >= 0) {
                notifyPosition = false;
                // Return true only if we weren't just breaking out of an animation
                return lastAnimation == IDLE;
            }
        } else {
            notifyPosition = true;
            lastPosition = position;
            lastMoving = Instant.now();
        }

        return false;
    }

    private void resetTimers() {
        final Player local = client.getLocalPlayer();

        // Reset animation idle timer
        lastAnimating = null;
        if (client.getGameState() == GameState.LOGIN_SCREEN || local == null || local.getAnimation() != lastAnimation) {
            lastAnimation = IDLE;
        }

        // Reset interaction idle timer
        lastInteracting = null;
        if (client.getGameState() == GameState.LOGIN_SCREEN || local == null || local.getInteracting() != lastInteract) {
            lastInteract = null;
        }
    }
}
