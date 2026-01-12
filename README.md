# Tempfly
Tempfly is a highly configurable Spigot/Bukkit plugin for Minecraft that introduces many flight related features to the game.

## Features
- Fly Time currency - Players can pay other players fly time.
- PvP & PvE toggles - You can set Flight to disable when a player is in combat, whether it be with another player or with hostile mobs.
- Bonus Time - Players can receive a fly time bonus (in seconds) when they first join and/or for each day that they login.
- Fly Speeds - Player fly speeds can be changed and limited in select regions or worlds.
- Timer Controls:
  - Offline - You can have the Fly Time pause when a player is offline or have X amount of Fly Time lost for every X number of seconds a player is offline (Flight Decay)
  - AFK - Players who are idle for X number of seconds can have their Fly Time paused until they return
  - Bonus - Players can gain fly time as a daily login incentive and as first time join bonus
  - Other Conditions - Fly timers can be paused if...
    - ...a player toggled off flight
    - ...a player is standing on the ground with flight enabled
    - ...a player is in Creative or Spectator Modes
- Regular Backups
- SQL support
- Aesthetics:
  - Flight Indicator - Player names in TAB and their tags can have text added to indicate they have Fly Time active (flying players without fly time will not have this indicator). Players will soon be able to toggle this on and off with "/tf display on/off".
  - Particles - Players can have particle trails while flying
- Warnings - When fly time is about to run out, players can recieve notice by a title and subsitle displayed on their screen
- Other features (uncommon, but available):
  - Relative Times - Allows you to control how fast Fly Time will be used in select worlds or regions (e.g.: If a player is in spawn, slow time use by half)
  - Shop GUI - TempFly has a built-in GUI for buying fly time. Ideal for those who don't want a whole shop GUI plugin

## Developers:
TempFly can be hooked into using the TempFlyAPI. this can be aquired using TempFly.getAPI().
There is lots of documentation in the source code that explains what to do but if you still have problems ChiefMoneyBags made a small tutorial which you can find here: https://www.youtube.com/watch?v=wERiwqX-Wmw
