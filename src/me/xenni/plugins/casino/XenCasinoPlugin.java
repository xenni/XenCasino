package me.xenni.plugins.casino;

import me.xenni.plugins.xencraft.ecosystem.*;
import me.xenni.plugins.xencraft.plugin.GenericXenCraftPlugin;
import me.xenni.plugins.xencraft.util.CommandUtil;
import me.xenni.plugins.xencraft.util.XenCraftPluginData;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockListener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.logging.Level;

public final class XenCasinoPlugin extends GenericXenCraftPlugin
{
    private static final class PlayerData implements Serializable
    {
        public String slotAmount = null;
        public String slotAmountMoneySystem = null;
    }

    private static final class SlotMachine implements Runnable
    {
        private static String[] slotSymbols = { "-","*","#","^","$" };
        private static ChatColor[] slotColors = { ChatColor.BLACK , ChatColor.RED, ChatColor.BLUE, ChatColor.GREEN, ChatColor.GOLD };
        private static Random slotRNG = new Random();

        public final Location signLocation;
        private final XenCasinoPlugin casinoPlugin;
        private final XenCraftEcoSystemPlugin ecoSystemPlugin;
        private final World signWorld;
        private final BukkitScheduler scheduler;
        private Sign displaySign;

        private volatile int bukkitTaskID = -1;
        private Player currentUser = null;
        private volatile ValueStore<Float> betAmount = null;
        private volatile boolean isRunning = false;
        private volatile boolean isValid = true;

        private int[] wheelSymbolState = { 0, 0, 0 };
        private int[] wheelColorState = { 0, 0, 0 };
        private int[] wheelTickCount = { 0, 0, 0 };

        //This function is very finely tuned. If changed, run the full playability test with /slottest.
        public static float calcPayoutFactor(int[] wheelSymbolState, int[] wheelColorState)
        {
            boolean symboltriple = false;
            boolean colortriple = false;
            int symbolpairpos = -1;
            int colorpairpos = -1;
            boolean symbolbonus = false;
            boolean colorbonus = false;

            if (wheelColorState[0] == wheelColorState[1] && wheelColorState[0] == wheelColorState[2])
            {
                colortriple = true;
                if (wheelColorState[0] == 4)
                {
                    colorbonus = true;
                }
            }

            if (wheelSymbolState[0] == wheelSymbolState[1] && wheelSymbolState[0] == wheelSymbolState[2])
            {
                symboltriple = true;

                if (wheelSymbolState[0] == 4)
                {
                    symbolbonus = true;

                    if (wheelColorState[1] == 4)
                    {
                        colorbonus = true;
                    }
                }
            }
            else if (wheelSymbolState[0] == wheelSymbolState[1])
            {
                symbolpairpos = 0;

                if (wheelSymbolState[0] == 4)
                {
                    symbolbonus = true;
                }
            }
            else if (wheelSymbolState[0] == wheelSymbolState[2])
            {
                symbolpairpos = 1;

                if (wheelSymbolState[0] == 4)
                {
                    symbolbonus = true;
                }
            }
            else if (wheelSymbolState[1] == wheelSymbolState[2])
            {
                symbolpairpos = 2;

                if (wheelSymbolState[1] == 4)
                {
                    symbolbonus = true;
                }
            }
            else
            {
                for (int i = 0; i < 3; i++)
                {
                    if (wheelSymbolState[i] == 4)
                    {
                        symbolbonus = true;
                        if (!colortriple && wheelColorState[i] == 4)
                        {
                            colorbonus = true;
                        }
                    }
                }
            }

            if (!colortriple)
            {
                if (symboltriple)
                {
                    if (wheelColorState[0] == wheelColorState[2])
                    {
                        colorpairpos = 1;
                        if (wheelColorState[0] == 4)
                        {
                            colorbonus = true;
                        }
                    }
                }
                else if (symbolpairpos > 0)
                {
                    switch (symbolpairpos)
                    {
                        case 0:
                            if (wheelColorState[0] == wheelColorState[1])
                            {
                                colorpairpos = 0;
                                if (wheelColorState[0] == 4)
                                {
                                    colorbonus = true;
                                }
                            }
                            break;
                        case 1:
                            if (wheelColorState[0] == wheelColorState[2])
                            {
                                colorpairpos = 1;
                                if (wheelColorState[0] == 4)
                                {
                                    colorbonus = true;
                                }
                            }
                            if (wheelSymbolState[1] == 4)
                            {
                                symbolbonus = true;
                                if (wheelColorState[1] == 4)
                                {
                                    colorbonus = true;
                                }
                            }
                            break;
                        case 2:
                            if (wheelColorState[1] == wheelColorState[2])
                            {
                                colorpairpos = 2;
                                if (wheelColorState[0] == 4)
                                {
                                    colorbonus = true;
                                }
                            }
                            break;
                    }
                }
            }

            float factor = 0;

            if (symboltriple)
            {
                factor += 1.75f;
            }
            if (colortriple)
            {
                factor += 1.5f;
            }
            if (symbolpairpos >= 0)
            {
                factor += 1.25f;
            }
            if (colorpairpos >= 0)
            {
                factor += 1.0f;
            }
            if (symbolbonus)
            {
                factor += 0.75f;
            }
            if (colorbonus)
            {
                factor += 0.25f;
            }

            return (factor == 0 ? 0.15f : (factor * 0.83f));
        }

        public SlotMachine(XenCasinoPlugin plugin, Location location)
        {
            casinoPlugin = plugin;
            signLocation = location;

            ecoSystemPlugin = casinoPlugin.ecoSystemPlugin;
            signWorld = location.getWorld();
            scheduler = casinoPlugin.getServer().getScheduler();
        }

        private void renderSlotWheels()
        {
            displaySign.setLine(2,
                (slotColors[wheelColorState[0]] + slotSymbols[wheelSymbolState[0]]) + " " +
                (slotColors[wheelColorState[1]] + slotSymbols[wheelSymbolState[1]]) + " " +
                (slotColors[wheelColorState[2]] + slotSymbols[wheelSymbolState[2]])
            );
        }

        private boolean updateDisplaySign()
        {
            boolean updateSuccessful = displaySign.update();
            if (!updateSuccessful)
            {
                stopImmediately(true);
            }

            return updateSuccessful;
        }

        public boolean init(boolean requiresSetup)
        {
            Block signBlock = signLocation.getBlock();
            if (requiresSetup && signBlock.getType() != Material.WALL_SIGN)
            {
                return false;
            }

            BlockState signBlockState = signBlock.getState();
            if (requiresSetup && (signBlockState == null || !(signBlockState instanceof Sign)))
            {
                return false;
            }
            displaySign = (Sign)signBlockState;

            if (requiresSetup)
            {
                displaySign.setLine(0, ChatColor.LIGHT_PURPLE + "[Slot]");
                displaySign.setLine(1, "");
                displaySign.setLine(3, "");
            }
            else if (!displaySign.getLine(0).equals(ChatColor.LIGHT_PURPLE + "[Slot]") || !displaySign.getLine(1).isEmpty() || !displaySign.getLine(3).isEmpty())
            {
                return false;
            }

            renderSlotWheels();
            return updateDisplaySign();
        }

        public void run()
        {
            boolean done = true;
            for (int i = 0; i < 3; i++)
            {
                if (wheelTickCount[i] != -1)
                {
                    wheelColorState[i] = slotRNG.nextInt(5);
                    wheelSymbolState[i] = slotRNG.nextInt(5);
                    int rand = slotRNG.nextInt(wheelTickCount[i] > 20 ? 3 : 20);
                    if (wheelTickCount[i] > 3 && rand == 0)
                    {
                        wheelTickCount[i] = -1;
                        signWorld.playEffect(signLocation, Effect.CLICK2, 0, 5);
                    }
                    else
                    {
                        done = false;
                        wheelTickCount[i]++;
                    }
                }
            }
            renderSlotWheels();

            if (!updateDisplaySign())
            {
                return;
            }

            if (done)
            {
                if (bukkitTaskID != -1) //WARNING: NOT THREAD SAFE
                {
                    scheduler.cancelTask(bukkitTaskID);
                    bukkitTaskID = -1;
                }

                doPayout();
                isRunning = false;
            }
            else
            {
                signWorld.playEffect(signLocation, Effect.CLICK1, 0, 3);
            }
        }

        private void doPayout()
        {
            float payoutfactor = calcPayoutFactor(wheelSymbolState, wheelColorState);

            if (betAmount == null) //stopImmediately has probably been called.
            {
                return;
            }

            ValueStore<Float> payout = betAmount.moneySystem.createValueStore();
            payout.setValue(payoutfactor * betAmount.getValue());

            Wallet userWallet;
            try
            {
                userWallet = ecoSystemPlugin.getWallet("player." + currentUser.getName(), null, null);
            }
            catch (IOException ex)
            {
                currentUser.sendMessage("[Casino] Unable to pay you: could not access your wallet.");
                return;
            }

            ValueStore<Float> userValueStore = userWallet.getValueStoreForMoneySystem(betAmount.moneySystem);
            if (userValueStore == null)
            {
                currentUser.sendMessage("[Casino] Unable to pay you: could not access your wallet's amount for the slot's money system.");
                return;
            }

            if (!userValueStore.addValue(payout.getValue()))
            {
                currentUser.sendMessage("[Casino] Unable to pay you: your wallet did not accept the payout.");
                return;
            }

            ValueStore<Float> profit = betAmount.moneySystem.createValueStore();
            profit.setValue(payout.getValue());
            profit.subtractValue(betAmount.getValue());

            betAmount = null; //in case stopImmediately is called, we do not want to re-pay the player.

            currentUser.sendMessage(
                    (payout.getIsNothing() ?
                            "[Casino] You lost all your money. Better luck next time!" :
                            ("[Casino] You won back " + payout.toString(false) + ".")
                    ) +
                            " (result: " + profit.toString(false) + ")"
            );
        }

        private void refundUser()
        {
            Wallet userWallet;
            try
            {
                userWallet = ecoSystemPlugin.getWallet("player." + currentUser.getName(), null, null);
            }
            catch (IOException ex)
            {
                currentUser.sendMessage("[Casino] Unable to refund you: could not access your wallet.");
                return;
            }

            ValueStore<Float> userValueStore = userWallet.getValueStoreForMoneySystem(betAmount.moneySystem);
            if (userValueStore == null)
            {
                currentUser.sendMessage("[Casino] Unable to refund you: could not access your wallet's amount for the slot's money system.");
                return;
            }

            if (!userValueStore.addValue(betAmount.getValue()))
            {
                currentUser.sendMessage("[Casino] Unable to refund you: your wallet did not accept the money.");
                return;
            }

            currentUser.sendMessage("[Casino] You have been refunded " + betAmount.toString(false) + ".");
        }

        public String runForPlayer(Player player)
        {
            if (!isValid)
            {
                return "The slot machine is broken.";
            }
            if (!player.hasPermission("xencasino.slot.use"))
            {
                return "You do not have permission to use this machine.";
            }
            if (isRunning)
            {
                return "The slot machine is busy.";
            }
            isRunning = true;

            currentUser = player;
            betAmount = casinoPlugin.getPlayerSlotAmount(currentUser);
            if (betAmount == null || betAmount.getIsNothing() || betAmount.getIsDeficit())
            {
                isRunning = false;
                return ("You have not bet anything or have an invalid bet. Use /" + casinoPlugin.getCommand("setslotbet").getName() + " to set a bet.");
            }

            Wallet userWallet;
            try
            {
                userWallet = ecoSystemPlugin.getWallet("player." + currentUser.getName(), null, null);
            }
            catch (IOException ex)
            {
                isRunning = false;
                return "Could not access your wallet.";
            }

            ValueStore<Float> userValueStore = userWallet.getValueStoreForMoneySystem(betAmount.moneySystem);
            if (userValueStore == null)
            {
                isRunning = false;
                return "Could not access your wallet's amount for the slot's money system.";
            }

            if (userValueStore.compare(betAmount) < 0)
            {
                isRunning = false;
                return "You can not afford to pay your current bet.";
            }

            if (!userValueStore.subtractValue(betAmount.getValue()))
            {
                isRunning = false;
                return "Your bet amount could not be withdrawn from your wallet.";
            }

            wheelTickCount[0] = 0;
            wheelTickCount[1] = 0;
            wheelTickCount[2] = 0;
            bukkitTaskID = scheduler.scheduleSyncRepeatingTask(casinoPlugin, this, 1, 5);
            if (bukkitTaskID == -1)
            {
                refundUser();
                isRunning = false;
                return "Unable to schedule slot machine. Try again.";
            }

            return null;
        }

        public void stopImmediately(boolean destroyed)
        {
            if (isValid && isRunning)
            {
                if (bukkitTaskID != -1) //WARNING: NOT THREAD SAFE
                {
                    scheduler.cancelTask(bukkitTaskID);
                    bukkitTaskID = -1;
                }

                currentUser.sendMessage("[Casino] A slot machine you were using has been " + (destroyed ? "destroyed" : "disabled") + ".");

                if (betAmount != null)
                {
                    refundUser();

                    betAmount = null;
                    isRunning = false;
                }
            }

            isValid = false;
        }
    }

    private static final class SlotMachineTest implements Runnable
    {
        private static float totalTests = 15625f; //5^3 * 5^3; for 5 symbols and 5 colors in 3 wheels
        private final CommandSender sender;
        public SlotMachineTest(CommandSender commandSender)
        {
            sender = commandSender;
        }

        public void run()
        {
            sender.sendMessage("[Casino] Running slot test...");

            float payout = 0f;
            float win = 0f;
            float even = 0f;
            float loss = 0f;
            float zero = 0f;
            float maxloss = 0f;
            float maxwin = 0f;
            float lost = 0f;
            float won = 0f;

            int[] s = new int[3];
            int[] c = new int[3];

            for (s[0] = 0; s[0] < 5; s[0]++)
            {
                for (c[0] = 0; c[0] < 5; c[0]++)
                {
                    for (s[1] = 0; s[1] < 5; s[1]++)
                    {
                        for (c[1] = 0; c[1] < 5; c[1]++)
                        {
                            for (s[2] = 0; s[2] < 5; s[2]++)
                            {
                                for (c[2] = 0; c[2] < 5; c[2]++)
                                {
                                    float payed = SlotMachine.calcPayoutFactor(s, c);
                                    float profit = (payed - 1f);
                                    if (profit > 0f)
                                    {
                                        maxwin = Math.max(maxwin, profit);
                                        won += profit;
                                        win++;
                                    }
                                    else if (profit == 0f)
                                    {
                                        even++;
                                    }
                                    else if (profit == -1f)
                                    {
                                        lost--;
                                        zero++;
                                    }
                                    else
                                    {
                                        maxloss = Math.min(maxloss, profit);
                                        lost += profit;
                                        loss++;
                                    }

                                    payout += payed;
                                }
                            }
                        }
                    }
                }
            }

            sender.sendMessage("[Casino] Slot Test Results:");
            sender.sendMessage("  Play Analysis: Ratio (total)");
            sender.sendMessage("    Wins:   " + (win / totalTests) + " (" + win + ")");
            sender.sendMessage("    Evens:  " + (even / totalTests) + " (" + even + ")");
            sender.sendMessage("    Losses: " + (loss / totalTests) + " (" + loss + ")");
            sender.sendMessage("    Zeros:  " + (zero / totalTests) + " (" + zero + ")");
            sender.sendMessage("  Cost Analysis: Average (max)");
            sender.sendMessage("    Win:  " + (won / totalTests) + " (" + maxwin + ")");
            sender.sendMessage("    Loss: " + (lost / totalTests) + " (" + maxloss + ")");
            sender.sendMessage("  Profit Analysis:");
            sender.sendMessage("    Pay Ratio: " + (payout / totalTests));
            sender.sendMessage("    Casino Profit Per Play: " + ((totalTests - payout) / totalTests));
        }
    }

    private static final class CasinoPlayerListener extends PlayerListener
    {
        private final XenCasinoPlugin casinoPlugin;

        public CasinoPlayerListener(XenCasinoPlugin plugin)
        {
            casinoPlugin = plugin;

            PluginManager manager = casinoPlugin.getServer().getPluginManager();

            manager.registerEvent(Event.Type.PLAYER_INTERACT, this, Event.Priority.Normal, plugin);
        }

        public void onPlayerInteract(PlayerInteractEvent event)
        {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK)
            {
                Block block = event.getClickedBlock();
                if (block.getType() == Material.WALL_SIGN)
                {
                    Location loc = block.getLocation();

                    for(SlotMachine slot : casinoPlugin.slotMachines)
                    {
                        if (slot.signLocation.equals(loc))
                        {
                            event.setCancelled(true);

                            String result = slot.runForPlayer(event.getPlayer());
                            if (result != null)
                            {
                                event.getPlayer().sendMessage("[Casino] Could not use slot machine: " + result);
                            }
                            return;
                        }
                    }

                    BlockState state = block.getState();
                    if (state != null && state instanceof Sign)
                    {
                        Sign sign = (Sign)state;
                        if (sign.getLine(0).equalsIgnoreCase("[slot]"))
                        {
                            Player player = event.getPlayer();
                            if (player.hasPermission("xencasino.slot.create"))
                            {
                                event.setCancelled(true);

                                SlotMachine machine = new SlotMachine(casinoPlugin, loc);
                                if (!machine.init(true))
                                {
                                    player.sendMessage("[Casino] Unable to create slot machine.");
                                    return;
                                }

                                casinoPlugin.slotMachines.add(machine);

                                casinoPlugin.log(player.getName() + " created a slot machine @ " + loc.toString());
                                player.sendMessage("[Casino] You created a slot machine.");
                            }
                            else
                            {
                                player.sendMessage("[Casino] You do not have permission to create a slot machine.");
                            }
                        }
                    }
                }
            }
        }
    }
    private static final class CasinoBlockListener extends BlockListener
    {
        private final XenCasinoPlugin casinoPlugin;

        public CasinoBlockListener(XenCasinoPlugin plugin)
        {
            casinoPlugin = plugin;

            PluginManager manager = casinoPlugin.getServer().getPluginManager();

            manager.registerEvent(Event.Type.BLOCK_BREAK, this, Event.Priority.Normal, plugin);
        }

        public void onBlockBreak(BlockBreakEvent event)
        {
            Block block = event.getBlock();
            if (block.getType() == Material.WALL_SIGN)
            {
                Location loc = block.getLocation();
                for (SlotMachine slot : casinoPlugin.slotMachines)
                {
                    if (slot.signLocation.equals(loc))
                    {
                        Player player = event.getPlayer();
                        if (player == null)
                        {
                            casinoPlugin.log("Slot machine @ " + loc.toString() + " destroyed by the environment.");
                        }
                        else
                        {
                            if (player.hasPermission("xencasino.slot.create"))
                            {
                                casinoPlugin.log("Slot machine @ " + loc.toString() + " destroyed by '" + player.getName() + "'.");
                            }
                            else
                            {
                                event.setCancelled(true);
                                player.sendMessage("You do not have permission to do that.");
                                return;
                            }
                        }

                        slot.stopImmediately(true);
                        casinoPlugin.slotMachines.remove(slot);

                        return;
                    }
                }
            }
        }
    }

    private XenCraftEcoSystemPlugin ecoSystemPlugin;
    private HashMap<Player, ValueStore<Float>> playerSlotAmounts = new HashMap<Player, ValueStore<Float>>();
    private ArrayList<SlotMachine> slotMachines = new ArrayList<SlotMachine>();
    private CasinoBlockListener blockListener;
    private CasinoPlayerListener playerListener;

    private ValueStore<Float> getPlayerSlotAmount(Player player)
    {
        if (playerSlotAmounts.containsKey(player))
        {
            return playerSlotAmounts.get(player);
        }
        else
        {
            try
            {
                XenCraftPluginData dataStore = getPluginDataForPlayer(player);
                PlayerData data = dataStore.getData();
                if (data == null)
                {
                    dataStore.setData(new PlayerData());
                    return null;
                }
                else
                {
                    if (data.slotAmount == null)
                    {
                        return null;
                    }
                    MoneySystem<?> candidateCurrencySystem = ecoSystemPlugin.moneySystems.get(data.slotAmountMoneySystem);
                    if (candidateCurrencySystem == null || !(candidateCurrencySystem instanceof CurrencySystem))
                    {
                        data.slotAmount = null;
                        data.slotAmountMoneySystem = null;
                        return null;
                    }
                    CurrencySystem currencySystem = (CurrencySystem)candidateCurrencySystem;
                    Float amount = currencySystem.parseRepresentation(data.slotAmount);
                    if (amount == null)
                    {
                        data.slotAmount = null;
                        data.slotAmountMoneySystem = null;
                        return null;
                    }
                    ValueStore<Float> value = currencySystem.createValueStore();
                    value.setValue(amount);

                    playerSlotAmounts.put(player, value);
                    return value;
                }
            }
            catch (IOException ex)
            {
                return null;
            }
        }
    }
    private boolean setPlayerSlotAmount(Player player, ValueStore<Float> amount)
    {
        if (!(amount.moneySystem instanceof CurrencySystem))
        {
            return false;
        }

        playerSlotAmounts.remove(player);
        playerSlotAmounts.put(player, amount);

        try
        {
            XenCraftPluginData dataStore = getPluginDataForPlayer(player);
            PlayerData data = dataStore.getData();
            if (data == null)
            {
                data = new PlayerData();
                dataStore.setData(data);
            }
            data.slotAmount = amount.toString(false);
            data.slotAmountMoneySystem = amount.moneySystem.name;
        }
        catch (IOException ex)
        {
            return false;
        }

        return true;
    }

    @SuppressWarnings("unchecked") //see below
    private boolean cmdexecSetSlotBet(CommandSender sender, String[] args)
    {
        if (args.length < 1 || args.length > 2)
        {
            return false;
        }
        if (sender.hasPermission("xencasino.slot.use"))
        {
            if (!(sender instanceof Player))
            {
                sender.sendMessage("[Casino] This command requires a player context.");
                return true;
            }
            Player player = (Player)sender;

            ValueStore<?> amount;
            if (args.length > 1)
            {
                MoneySystem<?> system = ecoSystemPlugin.moneySystems.get(args[1]);
                if (system == null)
                {
                    sender.sendMessage("[Casino] Unable to find money system '" + args[1] + "'.");
                    return true;
                }
                amount = ecoSystemPlugin.parseRepresentation(args[0], system);
            }
            else
            {
                amount = ecoSystemPlugin.parseRepresentation(args[0]);
            }
            if (amount == null)
            {
                sender.sendMessage("[Casino] Unable to parse amount.");
                return true;
            }
            if (!(amount.moneySystem instanceof CurrencySystem))
            {
                sender.sendMessage("[Casino] The amount provided does not represent a currency.");
                return true;
            }

            if (setPlayerSlotAmount(player, (ValueStore<Float>)amount)) //this cast will always succeed; moneySystem is an instance of CurrencySystem, which only provides ValueStore<Float>.
            {
                sender.sendMessage("[Casino] Bet successfully set.");
            }
            else
            {
                sender.sendMessage("[Casino] Unable to set bet: Your personal data store could not be accessed.");
            }
        }
        else
        {
            sender.sendMessage("[Casino] You do not have permission to do that.");
        }

        return true;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        if (label.equals("setslotbet"))
        {
            return cmdexecSetSlotBet(sender, CommandUtil.groupArgs(args));
        }
        else if (label.endsWith("slotbet"))
        {
            if (args.length != 0)
            {
                return false;
            }
            if (!sender.hasPermission("xencasino.slot.use"))
            {
                sender.sendMessage("[Casino] You do not have permission to do that.");
                return true;
            }
            if (!(sender instanceof Player))
            {
                sender.sendMessage("[Casino] This command requires a player context.");
                return true;
            }
            Player player = (Player)sender;

            ValueStore<Float> amount = getPlayerSlotAmount(player);
            if (amount == null)
            {
                player.sendMessage("[Casino] You do not have a slot bet set or your data store could not be accessed.");
            }
            else
            {
                player.sendMessage("[Casino] Your slot bet is " + amount.toString(false));
            }

            return true;
        }
        else if (label.equals("slottest"))
        {
            if (args.length != 0)
            {
                return false;
            }
            if (!sender.hasPermission("xencasino.slot.test"))
            {
                sender.sendMessage("[Casino] You do not have permission to do that.");
                return true;
            }

            if (getServer().getScheduler().scheduleAsyncDelayedTask(this, new SlotMachineTest(sender)) == -1)
            {
                sender.sendMessage("[Casino] Unable to schedule test.");
            }

            return true;
        }

        return false;
    }

    public void onPluginEnable()
    {
        ecoSystemPlugin = XenCraftEcoSystemPlugin.connectToXenCraftPlugin(this, "EcoSystem", XenCraftEcoSystemPlugin.class);
        if (ecoSystemPlugin == null)
        {
            log("Unable to connect to XenCraft EcoSystem!", Level.SEVERE, 1);
            log("Plugin will now be disabled.", 1);

            getServer().getPluginManager().disablePlugin(this);

            return;
        }
        log("Connected to XenCraft EcoSystem: '" + ecoSystemPlugin.getDescription().getVersion() + "'.", 1);

        blockListener = new CasinoBlockListener(this);
        playerListener = new CasinoPlayerListener(this);

        try
        {
            loadSlots();
        }
        catch (IOException ex)
        {
            log("Unable to load slot machines: " + ex.getMessage(), Level.SEVERE, 2);
        }
    }

    public void onPluginDisable()
    {
        log("Disabling slots...", 1);
        for (SlotMachine slot : slotMachines)
        {
            slot.stopImmediately(false);
        }

        try
        {
            saveSlots();
        }
        catch (IOException ex)
        {
            log("Unable to save slot machines: " + ex.getMessage(), Level.SEVERE, 2);
        }

        slotMachines.clear();
        playerSlotAmounts.clear();
    }

    private void loadSlots() throws IOException
    {
        log("Loading slot machines...", 1);

        File slotStore = new File(getDataFolder().getCanonicalPath() + "/slots.xcsd");
        if (!slotStore.exists())
        {
            log("Slot data file 'slots.xcsd' does not exist. No slots loaded.", 2);
            return;
        }

        BufferedReader rdr = new BufferedReader(new InputStreamReader(new FileInputStream(slotStore)));
        try
        {
            while (true)
            {
                String line = rdr.readLine();
                if (line == null)
                {
                    break;
                }
                if (line.isEmpty())
                {
                    continue;
                }

                String[] parts = line.split("\t");
                if (parts.length != 4)
                {
                    log("Invalid slot entry detected. Slot will not be loaded.", Level.WARNING, 2);
                    continue;
                }

                World world = getServer().getWorld(parts[0]);
                if (world == null)
                {
                    log("Invalid slot entry detected: world '" + parts[0] + "' could not be found. Slot will not be loaded.", Level.WARNING, 2);
                    continue;
                }

                Location loc;
                try
                {
                    loc = new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                }
                catch (NumberFormatException ex)
                {
                    log("Invalid slot entry detected: unable to parse number. Slot will not be loaded.", Level.WARNING, 2);
                    continue;
                }

                SlotMachine slot = new SlotMachine(this, loc);
                if (!slot.init(false))
                {
                    log("Slot machine @ " + loc.toString() + " is no longer valid. Slot will not be loaded.", Level.WARNING, 2);
                    continue;
                }

                slotMachines.add(slot);
            }
        }
        finally
        {
            rdr.close();
        }

        log("Loaded " + slotMachines.size() + " slot machines.", 2);
    }

    private void saveSlots() throws IOException
    {
        log("Saving slot machines...", 1);

        File slotStore = new File(getDataFolder().getCanonicalPath() + "/slots.xcsd");
        if (!slotStore.exists())
        {
            log("Slot data file 'slots.xcsd' does not exist. Creating...", 2);
            if (!slotStore.createNewFile())
            {
                log("Unable to create slot data file 'slots.xcsd'.", Level.SEVERE, 2);
                return;
            }
        }

        BufferedWriter wrtr = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(slotStore)));
        try
        {
            for (SlotMachine slot : slotMachines)
            {
                Location loc = slot.signLocation;
                wrtr.write(loc.getWorld().getName());
                wrtr.write("\t");
                wrtr.write(Integer.toString((int)loc.getX()));
                wrtr.write("\t");
                wrtr.write(Integer.toString((int)loc.getY()));
                wrtr.write("\t");
                wrtr.write(Integer.toString((int)loc.getZ()));
                wrtr.newLine();
            }

            wrtr.flush();
            wrtr.close();
        }
        catch (IOException ex)
        {
            wrtr.close();
            slotStore.delete();

            throw ex;
        }
    }
}