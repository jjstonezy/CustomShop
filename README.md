# SITE CHAOS — CustomShop 2.0.1

Complete GitHub-ready replacement of CustomShop, including source, shop configuration, plugin descriptor and GitHub Actions build workflow.

## Fixes
- Fixed nested category navigation (Decorations, Misc and their subcategories).
- All shop/category menus are compact 27-slot (3-row) inventories.
- Large item categories use 18 items per page, with previous/next arrows, keeping all items including config slots above 44.
- Fixed configured enchantment books and potions: correct enchantment/potion properties are included in the actual purchased item.
- Preserved your existing prices, sell-back at 70%, /menu, bounty and auction implementations.

## Build
Upload the files to the matching locations in your GitHub repository. GitHub Actions creates `CustomShop-JAR` on every push; download the generated `.jar`, replace the old `CustomShop` JAR on your BisectHosting server, and **restart** the server (not /reload).

**Back up** `plugins/CustomShop/config.yml`, auction and bounty data before installing. The included config.yml belongs in your *source repository*; avoid overwriting your live server configuration if you have made live edits.
