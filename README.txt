====================================================================
         Havester - Bamboo Cutter Mod (for CobblemonOrigins)
====================================================================

INSTALL

   1. Copy the bundled Havester-*.jar to any folder on your PC.
      Example: D:\Games\CobblemonOrigins\Havester\build\libs\

   2. Open PowerShell as Administrator in your game folder and run:

      powershell -ExecutionPolicy Bypass -File install-for-friend.ps1

      OR double-click install-for-friend.ps1 if you have file association.

   3. Restart the game.

====================================================================
HOW TO USE

   [K]  Start / Stop Bamboo Cutter
   [O]  Open Settings GUI

====================================================================
SETTINGS (press O)

  Min Bamboo Height      2 - 10    (only cut stacks this tall or more)
  Sell Threshold Stacks  1 - 10    (auto /shop when bamboo reaches N x 64)
   Unpause When Tabbed Out          (game keeps running when tabbed out)
   Auto Sell                        (toggle automatic /shop selling)
   Collect Drops                    (toggle dropped bamboo collection)
   Hold W Always                    (toggle auto-walk)
   Hold Space Always                (toggle auto-jump)

====================================================================
WHAT IT DOES

  1. Scan 32-block radius for bamboo stacks (min height configurable)
  2. Pathfind to the bamboo
  3. Cut block #2 (not the root)
  4. Collect dropped bamboo items
  5. When bamboo in inventory reaches threshold, auto-run /shop
  6. Click the category slot, then shift-right-click bamboo to sell
  7. If stuck for 5 seconds, mark target as bad and find next one
  8. If no bamboo, retry every 10 seconds (does not stop)
  9. Game stays unpaused when tabbing out (toggle with P)

====================================================================
REQUIREMENTS

  - Minecraft 1.21.1 with Fabric Loader 0.18.1+
  - Fabric API 0.116.7+
  - CobblemonOrigins modpack

====================================================================
